package com.fzm.chat.qrlogin

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.data.UserPreference
import com.fzm.chat.databinding.ActivityQrLoginBinding
import com.fzm.chat.databinding.DialogPwdBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.search.web3.GoWallet
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * PC 网页版扫码登录确认页
 *
 * 流程：扫码 → 弹确认页 → 二次验证（密码）→ 加密私钥 + 签名 → confirm
 *
 * 二维码灰度支持两种格式（见 [QrLoginPayload]）：
 * - v1：二维码自带 key/iv，直接 AES-GCM；
 * - v2（ECDH）：二维码只含 PC 临时公钥，APP 生成临时密钥对派生共享密钥后加密。
 */
@Route(path = MainModule.QR_LOGIN)
class QrLoginActivity : BizActivity() {

    companion object {
        private const val TAG = "QrLoginActivity"
    }


    private val binding by init { ActivityQrLoginBinding.inflate(layoutInflater) }
    private val delegate by inject<LoginDelegate>()

    override val root: View
        get() = binding.root

    private var sessionId: String? = null
    private var key: String? = null
    private var iv: String? = null

    /** v2（ECDH）：二维码里的 PC 临时公钥；v1 为空 */
    private var pubKey: String? = null

    override fun initView() {
        // 防截屏/录屏
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sessionId = intent?.getStringExtra("sessionId")
        key = intent?.getStringExtra("key")
        iv = intent?.getStringExtra("iv")
        // ECDH v2 开关默认关闭：关闭时忽略 pubKey，按老逻辑（必须带 key+iv）校验
        pubKey = if (ChatConfig.QR_LOGIN_ECDH) intent?.getStringExtra("pubKey") else null
        binding.tvDevice.text = getString(R.string.qr_login_device_desc)
        binding.tvSessionHint.text = getString(R.string.qr_login_session_hint)
    }

    override fun initData() {
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.btnConfirm.setOnClickListener { onConfirm() }
        binding.btnCancel.setOnClickListener { onCancel() }
    }

    override fun onBackPressed() {
        onCancel()
    }

    private fun onConfirm() {
        // v1 需要 key+iv；v2 需要 pubKey
        val missingSecret = pubKey.isNullOrEmpty() && (key.isNullOrEmpty() || iv.isNullOrEmpty())
        if (sessionId.isNullOrEmpty() || missingSecret) {
            toast(R.string.qr_login_invalid_qr)
            finish()
            return
        }
        // 风险提示：登录网页版前，先确认设备环境安全（所有账号均提示）
        showRiskWarningDialog()
    }

    /**
     * 风险提示弹窗：用户确认后才进入二次验证流程
     */
    private fun showRiskWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_login_risk_title)
            .setMessage(R.string.qr_login_risk_warning)
            .setNegativeButton(R.string.qr_login_cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.qr_login_risk_continue) { _, _ -> continueLogin() }
            .show()
    }

    /**
     * 二次验证：设置过聊天密码则要求输入，否则使用默认密码
     */
    private fun continueLogin() {
        val preference = UserPreference.getInstance()
        if (preference.hasChatPassword()) {
            showPasswordDialog()
        } else {
            doConfirm(preference.getMnemonicStringWithDefaultPwd())
        }
    }

    private fun showPasswordDialog() {
        val bindingDialog = DialogPwdBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(bindingDialog.root).create().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
        }
        bindingDialog.ivClose.setOnClickListener { dialog.dismiss() }
        bindingDialog.btnOk.setOnClickListener {
            val password = bindingDialog.etInput.text.toString()
            if (password.isEmpty()) {
                toast(R.string.my_wallet_password_tips)
                return@setOnClickListener
            }
            dialog.dismiss()
            lifecycleScope.launch {
                val mnem = withContext(Dispatchers.IO) {
                    UserPreference.getInstance().getMnemonicString(password)
                }
                if (mnem.isNullOrEmpty()) {
                    toast(R.string.qr_login_wrong_password)
                } else {
                    doConfirm(mnem)
                }
            }
        }
    }

    private fun doConfirm(mnem: String?) {
        // 优先使用本地保存的私钥，缺失时用助记词推导
        val preference = UserPreference.getInstance()
        var privateKey = preference.PRI_KEY
        if (privateKey.isEmpty() && !mnem.isNullOrEmpty()) {
            privateKey = GoWallet.getPrikey("BTY", mnem)
        }
        if (privateKey.isEmpty()) {
            toast(R.string.qr_login_private_key_failed)
            return
        }
        val address = preference.getAddress()
        if (address.isEmpty()) {
            toast(R.string.qr_login_private_key_failed)
            return
        }
        binding.btnConfirm.isEnabled = false
        lifecycleScope.launch {
            // v2（ECDH）：临时密钥对 + 共享密钥加密；v1：二维码自带的 key/iv 直接 AES-GCM
            var encrypted: String? = null
            var phonePubKey: String? = null
            if (!pubKey.isNullOrEmpty()) {
                val ecdh = QrLoginCrypto.ecdhEncryptPrivateKey(privateKey, pubKey!!)
                encrypted = ecdh?.first
                phonePubKey = ecdh?.second
            } else {
                encrypted = QrLoginCrypto.aesGcmEncrypt(privateKey, key!!, iv!!)
            }
            val accountPubKey = QrLoginCrypto.compressedPublicKeyHex(privateKey)
            if (encrypted == null || accountPubKey == null) {
                toast(R.string.qr_login_encrypt_failed)
                binding.btnConfirm.isEnabled = true
                return@launch
            }
            // 打印要传给前端（scan-node / PC 端）的参数
            Log.i(
                TAG,
                "confirm 参数: sessionId=$sessionId, address=$address, ecdh=${!pubKey.isNullOrEmpty()}, " +
                        "encryptedPrivateKey=$encrypted, publicKey=$accountPubKey, phonePubKey=$phonePubKey"
            )
            val result = QrLoginApi.confirm(
                ChatConfig.QR_LOGIN_SERVER,
                sessionId!!,
                address,
                encrypted,
                privateKey,
                accountPubKey,
                phonePubKey
            )
            binding.btnConfirm.isEnabled = true
            if (result.success) {
                toast(R.string.qr_login_confirm_success)
                finish()
            } else {
                toast(result.message)
            }
        }
    }

    private fun onCancel() {
        // 通知服务端取消登录（尽力而为）
        val preference = UserPreference.getInstance()
        val privateKey = preference.PRI_KEY
        if (privateKey.isNotEmpty() && !sessionId.isNullOrEmpty()) {
            val pubKey = QrLoginCrypto.compressedPublicKeyHex(privateKey)
            if (pubKey != null) {
                lifecycleScope.launch {
                    QrLoginApi.cancel(ChatConfig.QR_LOGIN_SERVER, sessionId!!, privateKey, pubKey)
                }
            }
        }
        finish()
    }
}
