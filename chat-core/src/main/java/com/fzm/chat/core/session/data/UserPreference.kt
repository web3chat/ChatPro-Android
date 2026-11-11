package com.fzm.chat.core.session.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.bean.KeyPair
import com.fzm.chat.core.utils.CipherUtils
import com.fzm.wallet.sdk.db.entity.PWallet
import com.zjy.architecture.Arch
import com.zjy.architecture.util.preference.IStorage
import com.zjy.architecture.util.preference.Preference
import com.zjy.architecture.util.preference.PreferenceDelegate
import com.zjy.architecture.util.preference.PreferenceStorage
import org.litepal.LitePal
import org.litepal.extension.find
import walletapi.Walletapi

/**
 * @author zhengjy
 * @since 2020/12/07
 * Description:
 */
class UserPreference(internal val id: String?) : Preference {

    companion object {

        private const val PREF_MNEMONIC_WORDS = "PREF_MNEMONIC_WORDS"
        private const val PREF_ADDRESS = "PREF_ADDRESS"
        private const val PREF_SHOP_ADDRESS = "PREF_SHOP_ADDRESS"
        private const val PREF_PUB_KEY = "PREF_PUB_KEY"
        private const val PREF_PRI_KEY = "PREF_PRI_KEY"
        private const val PREF_CHAT_KEY_PWD = "PREF_CHAT_KEY_PWD"
        private const val PREF_HAS_CHAT_KEY_PWD = "PREF_HAS_CHAT_KEY_PWD"
        private const val PREF_AUDIO_CHANNEL = "PREF_AUDIO_CHANNEL"
        private const val PREF_MSG_NOTIFY = "PREF_MSG_NOTIFY"
        private const val PREF_CALL_NOTIFY = "PREF_CALL_NOTIFY"

        private const val PREF_SET_MSG_NOTIFY = "PREF_SET_MSG_NOTIFY"
        private const val PREF_SET_CALL_NOTIFY = "PREF_SET_CALL_NOTIFY"

        @Volatile
        private var sInstance: UserPreference? = null

        init {
            // 保证在主线程执行
            Handler(Looper.getMainLooper()).post {
                AppPreference.address.observeForever {
                    if (it.isNullOrEmpty()) {
                        // address不同时重置UserPreference
                        sInstance?.clear()
                        sInstance = null
                    }
                }
            }
        }

        fun getInstance(): UserPreference {
            if (sInstance == null) {
                synchronized(this) {
                    if (sInstance == null) {
                        sInstance = UserPreference(AppPreference.ADDRESS)
                    }
                }
            }
            return sInstance!!
        }

        fun init(address: String) {
            if (sInstance == null || sInstance?.id != address) {
                synchronized(this) {
                    if (sInstance == null || sInstance?.id != address) {
                        sInstance = UserPreference(address)
                    }
                }
            }
        }
    }

    private val preference = Arch.context.getSharedPreferences("$id", Context.MODE_PRIVATE)

    override val sp: IStorage = PreferenceStorage(preference)

    fun clear() {
        MNEMONIC_WORDS = ""
        PUB_KEY = ""
        PRI_KEY = ""
        CHAT_KEY_PWD = ""
        HAS_CHAT_KEY_PWD = false
    }

    /**
     * 用户助记词
     */
    var MNEMONIC_WORDS by PreferenceDelegate(PREF_MNEMONIC_WORDS, "", sp, true)

    /**
     * 用户地址
     */
    var ADDRESS by PreferenceDelegate(PREF_ADDRESS, "", sp, true)

    /**
     * 商城绑定地址
     */
    var SHOP_ADDRESS by PreferenceDelegate(PREF_SHOP_ADDRESS, "", sp, true)

    /**
     * 用户公钥
     */
    var PUB_KEY by PreferenceDelegate(PREF_PUB_KEY, "", sp, true)

    /**
     * 用户私钥
     */
    var PRI_KEY by PreferenceDelegate(PREF_PRI_KEY, "", sp, true)

    /**
     * 用户消息密码
     */
    var CHAT_KEY_PWD by PreferenceDelegate(PREF_CHAT_KEY_PWD, "", sp, true)

    /**
     * 是否设置用户消息密码
     */
    var HAS_CHAT_KEY_PWD by PreferenceDelegate(PREF_HAS_CHAT_KEY_PWD, false, sp, true)

    /**
     * 音频播放渠道
     */
    var AUDIO_CHANNEL by PreferenceDelegate(PREF_AUDIO_CHANNEL, 1, sp, false)

    /**
     * 新消息通知
     */
    var NEW_MSG_NOTIFY by PreferenceDelegate(PREF_MSG_NOTIFY, true, sp, false)

    /**
     * 新音视频通知消息
     */
    var NEW_CALL_NOTIFY by PreferenceDelegate(PREF_CALL_NOTIFY, true, sp, false)

    /**
     * 消息通知设置
     */
    var SET_MSG_NOTIFY by PreferenceDelegate(PREF_SET_MSG_NOTIFY, false, sp, false)
    private val _msgNotify: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply { postValue(SET_MSG_NOTIFY) }
    }
    val msgNotify: LiveData<Boolean>
        get() = _msgNotify

    fun setMsgNotify() {
        SET_MSG_NOTIFY = true
        _msgNotify.value = true
    }

    /**
     * 音视频通知设置
     */
    var SET_CALL_NOTIFY by PreferenceDelegate(PREF_SET_CALL_NOTIFY, false, sp, false)
    private val _callNotify: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply { postValue(SET_CALL_NOTIFY) }
    }
    val callNotify: LiveData<Boolean>
        get() = _callNotify

    fun setCallNotify() {
        SET_CALL_NOTIFY = true
        _callNotify.value = true
    }

    /**
     * 钱包id
     */
    var WALLET_ID by PreferenceDelegate("WALLET_ID", -1L, sp, false)

    /**
     * 红包合约用的 EVM（0x）地址
     *
     * 领/发红包时用助记词派生（BIP44 m/44'/60'/0'/0/0），当时用户输过密码所以能拿到助记词；
     * 但红包记录页不能弹密码，若用户设了密聊密码，[getMnemonicStringWithDefaultPwd] 会失败，
     * 只能靠这里缓存的地址 + 钱包已落库的 Coin 地址兜底。
     */
    var RED_PACKET_EVM_ADDRESS by PreferenceDelegate("RED_PACKET_EVM_ADDRESS", "", sp, false)

    /**
     * 智能客服：本标签页标识（生成一个 UUID 后长期复用，参与 LiveKit identity `{uid}-{tab_id}`）
     */
    var CS_TAB_ID by PreferenceDelegate("CS_TAB_ID", "", sp, false)

    /**
     * 智能客服：上次会话 id（下次登录带上，用于继续上次那段对话）
     */
    var CS_SESSION_ID by PreferenceDelegate("CS_SESSION_ID", "", sp, false)

    fun getKeyPair(): KeyPair {
        return KeyPair(PRI_KEY, PUB_KEY)
    }

    fun saveMnemonicStringWithDefaultPwd(mnem: String): String? {
        return saveMnemonicString(mnem, CipherUtils.DEFAULT_PASSWORD)
    }

    fun saveMnemonicString(mnem: String, password: String): String? {
        val encPassword = CipherUtils.encryptPassword(password)
        val enemString = CipherUtils.seedEncKey(encPassword, mnem)
        HAS_CHAT_KEY_PWD = CipherUtils.DEFAULT_PASSWORD != password
        MNEMONIC_WORDS = enemString ?: ""
        CHAT_KEY_PWD = CipherUtils.passwordHash(encPassword) ?: ""
        return enemString
    }
    fun saveMnemonicString2(mnemHash: String, passwordHash: String): String {
        HAS_CHAT_KEY_PWD = true
        MNEMONIC_WORDS = mnemHash
        CHAT_KEY_PWD = passwordHash
        return mnemHash
    }

    fun getMnemonicStringWithDefaultPwd(): String? {
        return getMnemonicString(CipherUtils.DEFAULT_PASSWORD)
    }

    fun getMnemonicString(password: String): String? {
        val id = MyWallet.getID()
        return if (checkPassword(password,id)) {
            val encPassword = CipherUtils.encryptPassword(password)
            val pWallet = LitePal.find<PWallet>(id)
            if(pWallet!!.mnem.isNullOrEmpty()) {
                pWallet.mnem = MNEMONIC_WORDS
                pWallet.update(pWallet.id)
            }
            CipherUtils.seedDecKey(encPassword, pWallet.mnem)
        } else {
            null
        }
    }

    /**
     * 检查密聊密码是否正确
     */
    fun checkPassword(password: String,waletId:Long): Boolean {
        val pWallet = LitePal.find<PWallet>(waletId)
        if(pWallet!!.password.isNullOrEmpty()) {
            pWallet.password = CHAT_KEY_PWD
            pWallet.update(pWallet.id)
        }
        return CipherUtils.checkPassword(password, pWallet.password)
    }

    /**
     * 用户是否已设置助记词密码
     */
    fun hasChatPassword(): Boolean {
        return HAS_CHAT_KEY_PWD
    }

    /**
     * 用户是否已创建公私钥对
     */
    fun hasDHKeyPair(): Boolean {
        return PUB_KEY.isNotEmpty() && PRI_KEY.isNotEmpty()
    }

    /**
     * 保存用户公私钥对
     */
    fun saveDHKeyPairAndAddress(publicKey: String?, privateKey: String?, address: String?) {
        PUB_KEY = publicKey ?: ""
        PRI_KEY = privateKey ?: ""
        ADDRESS = address ?: ""
    }

    /**
     * 获取用户地址
     */
    fun getAddress(): String {
        var address = ADDRESS
        if (address.isEmpty()) {
            address = CipherUtils.pubToAddress(PUB_KEY)
            ADDRESS = address
            return address
        }
        return address
    }
}