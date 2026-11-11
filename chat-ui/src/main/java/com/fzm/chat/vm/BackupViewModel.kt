package com.fzm.chat.vm

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.fzm.chat.R
import com.fzm.chat.bean.mnem.CreateMnem
import com.fzm.chat.core.backup.LocalAccountManager
import com.fzm.chat.core.backup.LocalAccountType
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.BackupMnemonic
import com.fzm.chat.core.data.bean.BindAccount
import com.fzm.chat.core.data.bean.CheckSE
import com.fzm.chat.core.data.bean.GetShard
import com.fzm.chat.core.data.enum.CodeType
import com.fzm.chat.core.repo.BackupRepository
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.utils.CipherUtils
import com.fzm.chat.core.utils.formatMnemonic
import com.zjy.architecture.data.Event
import com.zjy.architecture.data.IGNORE_ERROR
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.ext.bytes2Hex
import com.zjy.architecture.ext.handleException
import com.zjy.architecture.mvvm.GlobalErrorHandler
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import walletapi.Walletapi

/**
 * @author zhengjy
 * @since 2021/01/12
 * Description:
 */
class BackupViewModel(
    private val repository: BackupRepository,
    private val manager: LocalAccountManager,
    delegate: LoginDelegate
) : LoadingViewModel(), LoginDelegate by delegate {

    private val _code by lazy { MutableLiveData<Int>() }
    val code: LiveData<Int>
        get() = _code

    private val _sendResult by lazy { MutableLiveData<Any>() }
    val sendResult: LiveData<Any>
        get() = _sendResult

    private val _storeShard by lazy { MutableLiveData<Any>() }
    val storeShard: LiveData<Any>
        get() = _storeShard

    private val _resetShard by lazy { MutableLiveData<Any>() }
    val resetShard: LiveData<Any>
        get() = _resetShard

    private val _bindAccount by lazy { MutableLiveData<BindAccount>() }
    val bindAccount: LiveData<BindAccount>
        get() = _bindAccount
    private val _queryChataddress by lazy { MutableLiveData<BindAccount>() }
    val queryChataddress: LiveData<BindAccount>
        get() = _queryChataddress
    private val _queryAccounts by lazy { MutableLiveData<BindAccount>() }
    val queryAccounts: LiveData<BindAccount>
        get() = _queryAccounts

    private val _getShard by lazy { MutableLiveData<GetShard>() }
    val getShard: LiveData<GetShard>
        get() = _getShard

    private val _checkShardExists by lazy { MutableLiveData<CheckSE>() }
    val checkShardExists: LiveData<CheckSE>
        get() = _checkShardExists

    private val _fetchResult by lazy { MutableLiveData<BackupMnemonic>() }
    val fetchResult: LiveData<BackupMnemonic>
        get() = _fetchResult

    private val _checkError by lazy { MutableLiveData<Event<Int>>() }
    val checkError: LiveData<Event<Int>>
        get() = _checkError

    private val _bindResult by lazy { MutableLiveData<String>() }
    val bindResult: LiveData<String>
        get() = _bindResult

    private val _queryResult by lazy { MutableLiveData<Boolean>() }
    val queryResult: LiveData<Boolean>
        get() = _queryResult

    private val _error by lazy { MutableLiveData<String>() }
    val error: LiveData<String>
        get() = _error

    private val _loginforMnem by lazy { MutableLiveData<String>() }
    val loginforMnem: LiveData<String>
        get() = _loginforMnem

    private val _needCreate by lazy { MutableLiveData<CreateMnem>() }
    val needCreate: LiveData<CreateMnem>
        get() = _needCreate

    private val _importResult by lazy { MutableLiveData<String>() }
    val importResult: LiveData<String>
        get() = _importResult

    private val _recoverResult by lazy { MutableLiveData<Event<Unit>>() }
    val recoverResult: LiveData<Event<Unit>>
        get() = _recoverResult

    private var countDownJob: Job? = null


    fun createMnemonic(account: String, code: String, loginType: Int, password: String) {
        launch {
            try {
                loading(true)
                val mnemWithSpace = CipherUtils.createMnemonicString(0, 128).formatMnemonic()
                val spliteMnem = Walletapi.spliteMnemonic(mnemWithSpace)
                val s1 = spliteMnem.shareKey1
                val s2 = spliteMnem.shareKey2
                val hdWallet = withContext(Dispatchers.IO) {
                    CipherUtils.getHDWallet(Walletapi.TypeBtyString, mnemWithSpace)
                }
                if (hdWallet == null) {
                    dismiss()
                    _error.value = "私钥创建失败"
                    return@launch
                }
                loginSuspend(
                    s2,
                    hdWallet.newKeyPub(0).bytes2Hex(),
                    hdWallet.newKeyPriv(0).bytes2Hex(),
                    mnemWithSpace, password, phone = account
                )
                val chataddress = hdWallet.newAddress_v2(0)
                val cm = CreateMnem().apply {
                    this.account = account
                    this.code = code
                    this.loginType = loginType
                    this.chatAddress = chataddress
                    this.mnemWithSpace = mnemWithSpace
                    this.s1 = s1
                    this.s2 = s2

                }
                _needCreate.value = cm
            } finally {
                dismiss()
            }
        }
    }

    fun loginForMnemonic(account: String, mnemonic: String, s2: String, password: String) {
        launch {
            try {
                loading(true)
                val hdWallet = withContext(Dispatchers.IO) {
                    CipherUtils.getHDWallet(Walletapi.TypeBtyString, mnemonic)
                }
                if (hdWallet == null) {
                    dismiss()
                    _error.value = "私钥创建失败"
                    return@launch
                }
                loginSuspend(
                    s2,
                    hdWallet.newKeyPub(0).bytes2Hex(),
                    hdWallet.newKeyPriv(0).bytes2Hex(),
                    mnemonic, password, phone = account
                )
                _loginforMnem.value = mnemonic
            } finally {
                dismiss()
            }
        }
    }

    fun sendCode(account: String, loginType: Int, codeType: CodeType) {
        request<Any> {
            onRequest {
                if (loginType == ChatConst.PHONE) {
                    repository.newSendPhoneCode(account, codeType)
                } else {
                    repository.sendEmailCodeV2(account, codeType)
                }
            }
            onSuccess {
                _sendResult.value = it
            }
        }
    }

    fun storeShard(phone: String, code: String, chataddress: String, shard_key: String) {
        request<Any> {
            onRequest {
                repository.storeShard(phone, code, chataddress, shard_key)
            }
            onSuccess {
                _storeShard.value = it
            }
        }
    }

    fun resetShard(phone: String, code: String, chataddress: String, shard_key: String) {
        request<Any> {
            onRequest {
                repository.resetShard(phone, code, chataddress, shard_key)
            }
            onSuccess {
                _resetShard.value = it
            }
        }
    }

    fun bindAccount(phone: String, wechat_id: String, xiaohongshu_id: String, douyin_id: String, chataddress: String) {
        request<BindAccount> {
            onRequest {
                repository.bindAccount(phone, wechat_id, xiaohongshu_id, douyin_id, chataddress)
            }
            onSuccess {
                _bindAccount.value = it
            }
        }
    }

    fun queryChataddress(keyword: String,) {
        request<BindAccount> {
            onRequest {
                repository.queryChataddress(keyword)
            }
            onSuccess {
                _queryChataddress.value = it
            }
        }
    }
    fun queryAccounts(chataddress: String) {
        request<BindAccount> {
            onRequest {
                val result = repository.queryAccounts(chataddress)
                if (result.isSucceed()) {
                    result
                } else {
                    Result.Error(ApiException(IGNORE_ERROR))
                }
            }
            onSuccess {
                _queryAccounts.value = it
            }
        }
    }

    fun getShard(phone: String, code: String) {
        request<GetShard> {
            onRequest {
                repository.getShard(phone, code)
            }
            onSuccess {
                _getShard.value = it
            }
        }
    }

    fun checkShardExists(phone: String) {
        viewModelScope.launch {
            val result = repository.checkShardExists(phone)
            _checkShardExists.value = result.dataOrNull()
        }
    }

    suspend fun sendCodeSuspend(account: String, loginType: Int, codeType: CodeType): Boolean {
        val result = try {
            if (loginType == ChatConst.PHONE) {
                repository.sendPhoneCodeV2(account, codeType)
            } else {
                repository.sendEmailCodeV2(account, codeType)
            }
        } catch (e: Exception) {
            Result.Error(handleException(e))
        }
        return if (result.isSucceed()) {
            true
        } else {
            GlobalErrorHandler.handler?.invoke(result.error())
            false
        }
    }

    private fun login(account: String, code: String, loginType: Int) {
        request<BackupMnemonic> {
            onRequest {
                if (loginType == ChatConst.PHONE) {
                    repository.fetchBackupByPhone(account, code)
                } else {
                    repository.fetchBackupByEmail(account, code)
                }
            }
            onSuccess {
                _fetchResult.value = it
            }
        }
    }

    /**
     * 尝试用指定密码直接导入
     */
    suspend fun autoImportMnemonic(enc: String, password: String): Boolean {
        loading(true)
        val mnemonic = withContext(Dispatchers.IO) {
            CipherUtils.decryptMnemonicString(enc, password)
        }
        return if (mnemonic == null) {
            dismiss()
            false
        } else {
            if (reImportMnemonic(mnemonic, password)) {
                dismiss()
                _checkError.value = Event(0)
                true
            } else {
                dismiss()
                false
            }
        }
    }

    /**
     * 使用用户输入密码导入
     */
    fun decryptAndReImportMnemonic(enc: String, password: String) {
        launch {
            loading(true)
            val mnemonic = withContext(Dispatchers.IO) {
                CipherUtils.decryptMnemonicString(enc, password)
            }
            if (mnemonic == null) {
                _checkError.value = Event(R.string.chat_tips_chat_pwd_error)
                dismiss()
            } else {
                if (reImportMnemonic(mnemonic, password)) {
                    dismiss()
                    _checkError.value = Event(0)
                } else {
                    dismiss()
                }
            }
        }
    }

    /**
     * 在忘记密码时，先尝试自动恢复助记词
     */
    suspend fun tryRecoverMnemonic(account: String, @LocalAccountType type: Int): Boolean {
        loading(true)
        val mnemonic = manager.tryRecoverMnemonic(account, type)
        if (!mnemonic.isNullOrEmpty()) {
            return if (reImportMnemonic(mnemonic, "", true)) {
                dismiss()
                _recoverResult.value = Event(Unit)
                true
            } else {
                dismiss()
                false
            }
        }
        dismiss()
        return false
    }

    private suspend fun reImportMnemonic(
        mnemonic: String,
        password: String,
        ignoreError: Boolean = false
    ): Boolean {
        val mnemWithSpace = mnemonic.formatMnemonic()
        val hdWallet = withContext(Dispatchers.IO) {
            CipherUtils.getHDWallet(Walletapi.TypeBtyString, mnemWithSpace)
        }
        if (hdWallet == null) {
            if (!ignoreError) {
                _checkError.value = Event(R.string.chat_login_mnem_import_fail)
            }
            return false
        }
        loginSuspend(
            hdWallet.newAddress_v2(0),
            hdWallet.newKeyPub(0).bytes2Hex(),
            hdWallet.newKeyPriv(0).bytes2Hex(),
            mnemWithSpace,
            password
        )
        return true
    }

    /**
     * 快速创建并导入助记词登录，用于在忘记密码的情况下
     */
    fun autoCreateAndImportMnemonic(account: String) = launch {
        loading(true)
        val mnemonic = CipherUtils.createMnemonicString(1, 160).formatMnemonic()
        val hdWallet = withContext(Dispatchers.IO) {
            CipherUtils.getHDWallet(Walletapi.TypeBtyString, mnemonic)
        }
        if (hdWallet == null) {
            dismiss()
            _importResult.value = null
            return@launch
        }
        loginSuspend(
            hdWallet.newAddress_v2(0),
            hdWallet.newKeyPub(0).bytes2Hex(),
            hdWallet.newKeyPriv(0).bytes2Hex(),
            mnemonic, ""
        )
        dismiss()
        _importResult.value = account
    }

    fun bindAccount(account: String, code: String, bindType: Int) {
        request<Any> {
            onRequest {
                val enc = preference.MNEMONIC_WORDS
                if (bindType == ChatConst.PHONE) {
                    repository.bindPhoneV2(account, code, enc)
                } else {
                    repository.bindEmailV2(account, code, enc)
                }
            }
            onSuccess {
                updateInfo {
                    if (bindType == ChatConst.PHONE) {
                        phone = account
                    } else {
                        email = account
                    }
                }
                _bindResult.value = account
            }
        }
    }

    fun bindAutoCreateAccount(account: String, code: String, bindType: Int) {
        request<Any> {
            onRequest {
                val enc = preference.MNEMONIC_WORDS
                if (bindType == ChatConst.PHONE) {
                    repository.bindPhone(account, code, enc)
                } else {
                    repository.bindEmail(account, code, enc)
                }
            }
            onSuccess {
                updateInfo {
                    if (bindType == ChatConst.PHONE) {
                        phone = account
                    } else {
                        email = account
                    }
                }
                _bindResult.value = account
            }
        }
    }

    fun checkBind(account: String, bindType: Int) {
        request<Map<String, *>> {
            onRequest {
                if (bindType == ChatConst.PHONE) {
                    repository.phoneQuery(account)
                } else {
                    repository.emailQuery(account)
                }
            }
            onSuccess {
                _queryResult.value = it["exists"] as Boolean
            }
        }
    }

    fun findLocalAccount(account: String, accountType: Int) = liveData {
        emit(manager.findLocalAccount(account, accountType)?.address)
    }

    fun startCount() {
        countDownJob?.cancel()
        countDownJob = launch {
            repeat(60) {
                _code.value = 60 - (it + 1)
                delay(1000)
            }
        }
    }

    fun cancelCount() {
        countDownJob?.cancel()
        _code.value = 0
    }

}