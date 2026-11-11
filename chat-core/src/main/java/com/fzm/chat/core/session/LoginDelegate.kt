package com.fzm.chat.core.session

import android.util.Log
import androidx.lifecycle.*
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.arch.connection.logic.MessageDispatcher
import com.fzm.chat.core.backup.LocalAccountManager
import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.bean.ChatQuery
import com.fzm.chat.core.data.bean.Field
import com.fzm.chat.core.data.bean.Server
import com.fzm.chat.core.logic.MessageSender
import com.fzm.chat.core.media.DownloadManager2
import com.fzm.chat.core.rtc.calling.RTCCalling
import com.fzm.chat.core.rtc.data.isOnCalling
import com.fzm.chat.core.rtc.data.isWaiting
import com.fzm.chat.core.session.data.UserPreference
import com.fzm.chat.core.session.data.UserRepository
import com.fzm.chat.core.utils.CipherUtils
import com.fzm.chat.router.core.CoreModule
import com.fzm.chat.router.core.CoreService
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.main.MainService
import com.fzm.chat.router.oa.CompanyUser
import com.fzm.chat.router.oa.OAModule
import com.fzm.chat.router.oa.OAService
import com.fzm.chat.router.oa.hasCompany
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.zjy.architecture.data.Result
import com.zjy.architecture.di.rootScope
import com.zjy.architecture.util.logE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.CoroutineContext

/**
 * @author zhengjy
 * @since 2020/08/06
 * Description:
 */
interface LoginDelegate {

    /**
     * 当前登录用户信息
     */
    val current: LiveData<UserInfo>

    /**
     * 当前登录用户企业信息
     */
    val companyUser: LiveData<CompanyUser>

    /**
     * 当用户信息或企业用户信息任意一项发生变化时会发出通知
     */
    val userInfoChanged: LiveData<Int>

    /**
     * 获取用户连接的所有服务器(包含企业服务器)
     */
    val servers: LiveData<List<Server>>

    /**
     * 当前用户信息本地存储信息
     */
    val preference: UserPreference

    /**
     * 登录事件
     */
    val loginEvent: LiveData<Boolean>

    /**
     * 登录成功之后设置公私钥对和地址，获取用户信息
     *
     * @param mnemonic  助记词，中文助记词需要用空格隔开
     */

    suspend fun loginSuspend(
        user: String,
        publicKey: String,
        privateKey: String,
        mnemonic: String,
        password: String,
        phone: String = "",
        email: String = ""
    )

    suspend fun loginSuspendCache(
        publicKey: String,
        privateKey: String,
        mnemonic: String,
        password: String
    )

    suspend fun loginSuspendCache2(
        publicKey: String,
        privateKey: String,
        mnemonic: String,
        mnemHash: String,
        passwordHash: String
    )

    /**
     * 注销操作
     */
    fun performLogout()

    suspend fun logoutSuspend()

    /**
     * 从服务端更新用户信息
     */
    fun updateInfo()

    /**
     * 从服务端更新企业和用户信息
     */
    fun updateCompanyInfo()

    /**
     * 手动更新本地用户信息
     */
    fun updateInfo(block: UserInfo.() -> Unit)

    /**
     * 修改用户信息
     */
    suspend fun setUserInfo(fields: List<Field>): Result<String>

    /**
     * 获取用户地址
     */
    fun getAddress(): String?

    /**
     * 是否已经登录
     */
    fun isLogin(): Boolean

    /**
     * 是否是企业用户（或关联了企业帐号）
     */
    fun hasCompany(): Boolean

    /**
     * 是否是自动登录
     */
    fun isAutoLogin(): Boolean
}

class LoginDelegateImpl(
    private val repository: UserRepository,
    private val manager: LocalAccountManager
) : LoginDelegate, CoroutineScope {

    private val coreService by route<CoreService>(CoreModule.SERVICE)
    private val mainService by route<MainService>(MainModule.SERVICE)
    private val walletService by route<WalletService>(WalletModule.SERVICE)
    private val oaService by route<OAService>(OAModule.SERVICE)

    private val dispatcher by rootScope.inject<MessageDispatcher>()
    private val messageManager by rootScope.inject<MessageSender>()
    private val rtcCall by rootScope.inject<RTCCalling>()

    private var autoLogin = true

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Main.immediate

    override val current: LiveData<UserInfo> = liveData {
        emitSource(AppPreference.address.switchMap { address ->
            if (address.isNotEmpty()) {
                repository.getUserInfoLive(address)
            } else {
                liveData { emit(UserInfo.EMPTY_USER) }
            }
        }.distinctUntilChanged())
        if (AppPreference.ADDRESS.isNotEmpty()) {
            // 如果一开始就登录了，则更新用户信息
            repository.getUserInfo(AppPreference.ADDRESS, preference.PUB_KEY, chatQuery)
            coreService?.checkService()
            _loginEvent.value = true
        }
    }

    override val companyUser: LiveData<CompanyUser> = liveData {
        emitSource(AppPreference.address.switchMap { address ->
            if (address.isNotEmpty()) {
                repository.getCompanyUserLive(address)
            } else {
                liveData { emit(CompanyUser.EMPTY_COMPANY_USER) }
            }
        }.distinctUntilChanged())
        if (AppPreference.ADDRESS.isNotEmpty()) {
            // 查自己的企业用户信息时不需要传地址
            oaService?.getCompanyUser(null)
        }
    }

    override val userInfoChanged: LiveData<Int> = liveData {
        launch {
            current.asFlow().collect { emit(1) }
        }
        launch {
            companyUser.asFlow().collect { emit(1) }
        }.join()
        // join是为了挂起这个协程，防止因为作用域结束，而导致不能发送事件
    }

    override val servers: LiveData<List<Server>> = userInfoChanged.switchMap {
        if (hasCompany()) {
            companyUser.map {
                val userServers = current.value?.servers ?: emptyList()
                if (it.company?.imServer.isNullOrEmpty()) {
                    userServers
                } else {
                    userServers.toMutableList().apply {
                        add(0, Server("company", it.company!!.name, it.company!!.imServer))
                    }
                }
            }
        } else {
            current.map { it.servers }
        }
    }

    override val preference: UserPreference
        get() = UserPreference.getInstance()

    override val loginEvent: LiveData<Boolean>
        get() = _loginEvent

    private val _loginEvent = MutableLiveData<Boolean>()

    private val chatQuery: ChatQuery
        get() = ChatQuery.userQuery(
            preference.ADDRESS,
            preference.ADDRESS,
            "",
            preference.getKeyPair()
        )

    private val changeLocalAccountObserver = Observer<UserInfo> {
        if (it.isLogin()) launch { manager.store(it) }
    }

    init {
        current.observeForever(changeLocalAccountObserver)
    }

    /**
     * 登录成功后把本地各链收款地址同步到链上资料（幂等，失败由 WalletDataSource 内部自动重试），
     * 确保其他用户能按链查到本账号的收款地址。
     *
     * 注意：调用点必须放在 repository.getUserInfo 之后，且先等当前用户信息 LiveData 就绪，
     * 否则冷启动/弱网时会因“用户信息为空”或会话未就绪白跑一趟；
     * 本次失败后仍由主界面 / “我的”页的兜底重试继续补传。
     */
    private suspend fun syncChainAddress() {
        runCatching {
            // 等待用户信息首值就绪（最多 5 秒）
            val ready = withTimeoutOrNull(5_000L) {
                while (current.value == null) delay(200L)
                true
            } == true
            if (!ready) {
                logE("收款地址同步：等待用户信息就绪超时，跳过本次")
                return@runCatching
            }
            val ret = walletService?.updateChainAddress(null)
            if (ret != null && ret != "ok") {
                logE("收款地址同步失败：$ret（将由主界面/我的页兜底重试）")
            }
        }
    }


    override suspend fun loginSuspend(
        user: String,
        publicKey: String,
        privateKey: String,
        mnemonic: String,
        password: String,
        phone: String,
        email: String
    ) {
        autoLogin = false
        // 登录/切号：先清掉上一个账户内存里的密码缓存
        SessionKeyCache.clear()
        val address = CipherUtils.pubToAddress(publicKey)
        UserPreference.init(address)
        AppPreference.ADDRESS = address
        withContext(Dispatchers.IO) {
            preference.saveDHKeyPairAndAddress(publicKey, privateKey, address)
            manager.backupMnemonic(address, privateKey, mnemonic)
            if (password.isEmpty()) {
                preference.saveMnemonicStringWithDefaultPwd(mnemonic)
            } else {
                preference.saveMnemonicString(mnemonic, password)
            }
        }
        oaService?.getCompanyUser(null)
        try {
            walletService?.importMnem(user, mnemonic, address, password, phone, email)
        } catch (e: Exception) {
            logE("钱包创建失败，${e.message}")
        }
        // 先拉取（或读取缓存的）用户信息，再同步收款地址，避免用户信息未就绪时白跑
        repository.getUserInfo(address, preference.PUB_KEY, chatQuery)
        //mainService?.addInnateDynamicShortcuts()
        _loginEvent.value = true
        syncChainAddress()
    }

    override suspend fun loginSuspendCache(
        publicKey: String,
        privateKey: String,
        mnemonic: String,
        password: String
    ) {
        autoLogin = false
        // 登录/切号：先清掉上一个账户内存里的密码缓存
        SessionKeyCache.clear()
        val address = CipherUtils.pubToAddress(publicKey)
        UserPreference.init(address)
        AppPreference.ADDRESS = address
        Log.v("master", "设置地址：${AppPreference.ADDRESS}")
        withContext(Dispatchers.IO) {
            preference.saveDHKeyPairAndAddress(publicKey, privateKey, address)
            manager.backupMnemonic(address, privateKey, mnemonic)
            if (password.isEmpty()) {
                preference.saveMnemonicStringWithDefaultPwd(mnemonic)
            } else {
                preference.saveMnemonicString(mnemonic, password)
            }
        }
        oaService?.getCompanyUser(null)
        repository.getUserInfo(address, preference.PUB_KEY, chatQuery)
        //mainService?.addInnateDynamicShortcuts()
        _loginEvent.value = true
        coreService?.checkService()
        // 切换账号后同样补一次收款地址同步（原先该路径缺失该触发点）
        syncChainAddress()
    }

    override suspend fun loginSuspendCache2(
        publicKey: String,
        privateKey: String,
        mnemonic: String,
        mnemHash: String,
        passwordHash: String
    ) {
        autoLogin = false
        // 登录/切号：先清掉上一个账户内存里的密码缓存
        SessionKeyCache.clear()
        val address = CipherUtils.pubToAddress(publicKey)
        UserPreference.init(address)
        AppPreference.ADDRESS = address
        Log.v("master", "设置地址：${AppPreference.ADDRESS}")
        withContext(Dispatchers.IO) {
            preference.saveDHKeyPairAndAddress(publicKey, privateKey, address)
            manager.backupMnemonic(address, privateKey, mnemonic)
            preference.saveMnemonicString2(mnemHash, passwordHash)
        }
        oaService?.getCompanyUser(null)
        repository.getUserInfo(address, preference.PUB_KEY, chatQuery)
        //mainService?.addInnateDynamicShortcuts()
        _loginEvent.value = true
        coreService?.checkService()
        // 放到 getUserInfo 与服务启动之后，避免会话未就绪时白跑（失败由主界面/我的页兜底重试）
        syncChainAddress()
    }

    override fun performLogout() {
        launch { logoutSuspend() }
    }


    //    override suspend fun logoutSuspend() {
//        if (rtcCall.getCurrentTask().isOnCalling) {
//            rtcCall.hangup().join()
//        } else if (rtcCall.getCurrentTask().isWaiting) {
//            rtcCall.reject().join()
//        }
//        walletService?.closeWallet()
//        DownloadManager2.dispose()
//        messageManager.dispose()
//        dispatcher.dispose()
//        val address = AppPreference.ADDRESS
//        AppPreference.ADDRESS = ""
//        repository.logout(address)
//        mainService?.removeAllDynamicShortcuts()
//        _loginEvent.value = false
//    }
    override suspend fun logoutSuspend() {
        _loginEvent.value = false
        // 退出登录时清掉会话内缓存的密码
        SessionKeyCache.clear()
        DownloadManager2.dispose()
        messageManager.dispose()
        dispatcher.dispose()
        Log.v("chat", "当前地址：${AppPreference.ADDRESS}")
        val address = AppPreference.ADDRESS
        AppPreference.ADDRESS = ""
        repository.logout(address)
        mainService?.removeAllDynamicShortcuts()
    }

    override fun updateInfo() {
        if (!isLogin()) return
        launch {
            repository.getUserInfo(preference.ADDRESS, preference.PUB_KEY, chatQuery)
        }
    }

    override fun updateCompanyInfo() {
        if (!isLogin()) return
        launch {
            oaService?.getCompanyUser(null)
        }
    }

    override fun updateInfo(block: UserInfo.() -> Unit) {
        if (!isLogin()) return
        launch {
            val info = current.value?.newBuilder()?.build()?.apply(block)
            repository.updateUserInfo(info)
        }
    }

    override suspend fun setUserInfo(fields: List<Field>): Result<String> {
        return repository.setUserInfo(fields)
    }

    override fun getAddress(): String? {
        return current.value?.address
    }

    override fun isLogin(): Boolean {
        val info = current.value
        // current 是 liveData{} 冷流，冷启动时需要等 Room 查询返回才有首个值，
        // 在此之前 current.value 为 null，不能当成未登录（LoginInterceptor 据此判断是否跳登录页，
        // 曾出现“已登录但偶尔跳 ChooseLoginActivity，杀进程重进又正常”的启动竞态）。
        // 这里用本地地址兜底：ADDRESS 只在登录时写入、登出时同步清空
        if (info == null) return AppPreference.ADDRESS.isNotEmpty()
        return info.isLogin()
    }

    override fun hasCompany(): Boolean {
        return companyUser.value.hasCompany
    }

    override fun isAutoLogin(): Boolean {
        return autoLogin
    }
}

fun LoginDelegate.checkLogin(redirect: (() -> Unit)? = null, action: () -> Unit) {
    if (isLogin()) {
        action()
    } else if (redirect != null) {
        redirect()
    }
}

fun LoginDelegate.checkLogin(router: String? = null, action: () -> Unit) {
    if (isLogin()) {
        action()
    } else if (router != null) {
        ARouter.getInstance().build(router).navigation()
    }
}
