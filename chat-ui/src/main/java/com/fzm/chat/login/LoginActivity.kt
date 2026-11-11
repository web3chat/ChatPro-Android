package com.fzm.chat.login

import android.content.Intent
import android.util.Log
import android.util.Patterns
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.ui.PasswordVerifyFragment
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.enum.CodeType
import com.fzm.chat.core.logic.ServerManager
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.utils.CipherUtils
import com.fzm.chat.databinding.ActivityLoginBinding
import com.fzm.chat.router.app.AppModule
import com.fzm.chat.router.biz.PasswordVerifier
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.route
import com.fzm.chat.vm.BackupViewModel
import com.fzm.chat.widget.ExportWordsDialog
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.widget.dialog.EasyDialog
import com.king.zxing.Intents
import com.zjy.architecture.ext.bytes2Hex
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.visible
import com.zjy.architecture.util.KeyboardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.anko.toast
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.litepal.LitePal
import org.litepal.extension.find
import org.litepal.extension.findFirst
import walletapi.Walletapi

/**
 * @author zhengjy
 * @since 2021/01/12
 * Description:
 */
@Route(path = MainModule.LOGIN)
class LoginActivity : BizActivity() {

    //s1是否已绑定
    private var sExists: Int = 0

    companion object {
        const val PHONE_LOGIN = ChatConst.PHONE
        const val EMAIL_LOGIN = ChatConst.EMAIL
    }

    private var mAccount: String = ""
        get() = field.replace(" ".toRegex(), "")

    /**
     * 登录类型
     * 0：手机
     * 1：邮箱
     */
    private var loginType = PHONE_LOGIN

    private val viewModel by viewModel<BackupViewModel>()
    private val delegate by inject<LoginDelegate>()

    private val binding by init { ActivityLoginBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    override fun initView() {
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.code.observe(this) {
            if (it > 0) {
                binding.tvGetCode.text = getString(R.string.login_tips_code_count, it)
                binding.tvGetCode.isClickable = false
            } else {
                binding.tvGetCode.setTextColor(resources.getColor(R.color.biz_color_accent))
                binding.tvGetCode.setText(R.string.login_action_send_code)
                binding.tvGetCode.isClickable = true
            }
        }
        viewModel.sendResult.observe(this) {
            if (it != null) {
                binding.tvGetCode.setTextColor(resources.getColor(R.color.biz_text_grey_light))
                viewModel.startCount()
                toast(R.string.login_tips_code_sent)
            }
        }
        viewModel.fetchResult.observe(this) {
            if (it.mnemonic.isNullOrEmpty()) {
                // 正常情况下不会进入这里，因为fetch之前已经确认过存在备份的助记词
                val dialog = EasyDialog.Builder()
                    .setHeaderTitle(getString(R.string.biz_tips))
                    .setBottomLeftText(getString(R.string.chat_login_create_account))
                    .setBottomRightText(getString(R.string.chat_login_import_account))
                    .setCancelable(false)
                    .setContent(getString(R.string.login_tips_unbind_yet))
                    .setBottomLeftClickListener { dialog ->
                        dialog.dismiss()
                        ARouter.getInstance().build(MainModule.CREATE_MNEM).navigation()
                        finish()
                    }
                    .setBottomRightClickListener { dialog ->
                        dialog.dismiss()
                        ARouter.getInstance().build(MainModule.IMPORT_ACCOUNT).navigation()
                        finish()
                    }.create(this)
                dialog.show()
            } else {
                lifecycleScope.launch {
                    if (viewModel.autoImportMnemonic(it.mnemonic ?: "", "hz123456")) {
                        // 先尝试使用固定密码解密助记词
                        autoBindPhoneToAccount()
                        ARouter.getInstance().build(AppModule.MAIN).navigation()
                        dismiss()
                        finish()
                    } else {
                        val dialog by route<DialogFragment>(
                            MainModule.DECRYPT_MNEMONIC,
                            bundleOf(
                                "encMnemonic" to it.mnemonic,
                                "account" to mAccount,
                                "loginType" to loginType
                            )
                        )
                        // 用户手动输入密码解密助记词
                        dialog?.show(supportFragmentManager, "DECRYPT_MNEMONIC")
                    }
                }
            }
        }
        viewModel.loginforMnem.observe(this) {
//            val dialog by route<PasswordVerifyFragment>(
//                MainModule.SET_ENC_PWD,
//                bundleOf("showWords" to 0)
//            )
//            dialog?.setOnPasswordVerifyListener(object : PasswordVerifier.OnPasswordVerifyListener {
//                override fun onSuccess(password: String) {
//                    gotoMain()
//                }
//            })
//            dialog?.show(supportFragmentManager, "SET_ENC_PWD")

            gotoMain()
        }
        viewModel.needCreate.observe(this) { params ->

            val exDialog = ExportWordsDialog(this, params.s2, 3)
            exDialog.setOnCloseListener(object : ExportWordsDialog.IOnCloseListener {
                override fun close() {
                    viewModel.storeShard(
                        params.account,
                        params.code,
                        params.chatAddress,
                        params.s1
                    )
                }

            })
            exDialog.show()

        }

        viewModel.storeShard.observe(this, Observer {
            gotoMain()
        })
        viewModel.bindResult.observe(this) {
            gotoMain()
        }
        viewModel.error.observe(this) { toast(it) }
    }


    private fun gotoMain() {
        autoBindPhoneToAccount()
        ARouter.getInstance().build(AppModule.MAIN).navigation()
        dismiss()
        finish()
    }

    /**
     * 手机号登录成功后，把登录手机号自动绑定到当前账号，
     * 使「安全管理-手机号」栏能直接显示该手机号。
     */
    private fun autoBindPhoneToAccount() {
        if (loginType != PHONE_LOGIN) return
        val phone = mAccount
        if (phone.isEmpty()) return
        val address = delegate.current.value?.address
        if (address.isNullOrEmpty()) return
        Log.d("LoginAutoBind", "auto bind phone=$phone address=$address")
        viewModel.bindAccount(phone, "", "", "", address)
    }

    override fun initData() {

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            val result = intent?.getStringExtra(Intents.Scan.RESULT)
            binding.etS2.setText(result)
        }

    }

    private val REQUEST_CODE = 1
    override fun setEvent() {
        binding.ivScan.setOnClickListener {
            ARouter.getInstance().build(MainModule.QR_SCAN).withBoolean("forceReturn", true)
                .navigation(this, REQUEST_CODE)
        }
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.ctbTitle.setOnRightClickListener {
            viewModel.cancelCount()
            if (loginType == PHONE_LOGIN) {
                loginType = EMAIL_LOGIN
                binding.ctbTitle.setRightText(getString(R.string.login_use_phone))
                binding.tvTitle.setText(R.string.login_use_email)
                binding.tvSubTitle.setText(R.string.login_use_email_tips)
                binding.etPhone.gone()
                binding.llArea.gone()
                binding.etEmail.visible()
                binding.etPhone.setText("")
                binding.etEmail.setText("")
                binding.etCode.setText("")
                binding.etEmail.post {
                    binding.etEmail.requestFocus()
                }
            } else {
                loginType = PHONE_LOGIN
                binding.ctbTitle.setRightText(getString(R.string.login_use_email))
                binding.tvTitle.setText(R.string.login_use_phone)
                binding.tvSubTitle.setText(R.string.login_use_phone_tips)
                binding.etPhone.visible()
                binding.llArea.visible()
                binding.etEmail.gone()
                binding.etPhone.setText("")
                binding.etEmail.setText("")
                binding.etCode.setText("")
                binding.etPhone.post {
                    binding.etPhone.requestFocus()
                }
            }
        }
        binding.etPhone.doOnTextChanged { text, start, count, after ->
            if (after == 1) {
                val length = text.toString().length
                if (length == 3 || length == 8) {
                    binding.etPhone.setText("$text ")
                    binding.etPhone.setSelection(binding.etPhone.text.toString().length)
                }
            }
            if (start == 3 || start == 8) {
                if (count == 1 && after == 0) {
                    binding.etPhone.setText(text?.subSequence(0, text.length - 1))
                    binding.etPhone.setSelection(binding.etPhone.text.toString().length)
                }
            }
            mAccount = text.toString()
        }
        binding.etEmail.addTextChangedListener {
            mAccount = it?.toString()?.trim() ?: ""
        }
        binding.tvGetCode.setOnClickListener {
            if (checkAccount()) {
                if (sExists == 1) {
                    viewModel.sendCode(mAccount, loginType, CodeType.GET)
                } else {
                    viewModel.sendCode(mAccount, loginType, CodeType.BACKUP)
                }

            }
        }
        binding.btnLogin.setOnClickListener {
            Log.v("cc", "sExists = $sExists")
            if (sExists != 0) {
                if (checkAccount() && checkCode() && checkServer()) {
                    val wallets = LitePal.where("phone = ?", mAccount).findFirst<PWallet>()
                    val s2 = wallets?.user
                    if (sExists == 1) {
                        //已经绑定过
                        if (!s2.isNullOrEmpty()) {
                            //本地有s2，直接验证码登录
                            val code = binding.etCode.text.toString().trim()
                            viewModel.getShard(mAccount, code)
                        } else {
                            if (checkS2() && checkPassword()) {
                                val code = binding.etCode.text.toString().trim()
                                viewModel.getShard(mAccount, code)
                            }
                        }
                    } else {
                        //新用户
                        if (checkPassword()) {
                            val code = binding.etCode.text.toString().trim()
                            val password = binding.etPassword.text.toString().trim()
                            viewModel.createMnemonic(mAccount, code, loginType, password)
                        }
                    }

                }
            } else {
                if (checkAccount() && checkServer()) {

                    KeyboardUtils.hideKeyboard(binding.btnLogin)

                    viewModel.checkShardExists(mAccount)

                    //viewModel.verifyCompanyUser(mAccount, code, loginType)
                }
            }


        }

        viewModel.checkShardExists.observe(this, Observer { checkSE ->
            sExists = if (checkSE.exists) 1 else -1
            Log.v("cc", "checkShardExists sExists =$sExists")
            if (sExists == 1) {

                val wallets = LitePal.where("phone = ?", mAccount).findFirst<PWallet>()
                val s2 = wallets?.user
                if (!s2.isNullOrEmpty()) {
                    binding.llGetCode.visibility = View.VISIBLE
                    binding.etPassword.visibility = View.GONE
                    binding.llGetS2.visibility = View.GONE

                } else {
                    binding.llGetCode.visibility = View.VISIBLE
                    binding.llGetS2.visibility = View.VISIBLE
                    binding.etPassword.visibility = View.VISIBLE
                }

            } else {
                binding.llGetCode.visibility = View.VISIBLE
                binding.llGetS2.visibility = View.GONE
                binding.etPassword.visibility = View.VISIBLE
            }

        })

        viewModel.getShard.observe(this, Observer { param ->
            val s1 = param.shard_key
            val pWallet = LitePal.where("phone = ?", mAccount).findFirst<PWallet>()
            val s2 = pWallet?.user
            if (!s2.isNullOrEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val mnem = Walletapi.recoverMnemonic(s1, s2)
                    val hdWallet = CipherUtils.getHDWallet(Walletapi.TypeBtyString, mnem)!!
                    val priv = hdWallet.newKeyPriv(0).bytes2Hex()
                    val pub = hdWallet.newKeyPub(0).bytes2Hex()
                    viewModel.loginSuspendCache2(pub, priv, mnem, pWallet.mnem, pWallet.password)
                    MyWallet.setId(pWallet.id)
                    withContext(Dispatchers.Main) {
                        autoBindPhoneToAccount()
                        ARouter.getInstance().build(AppModule.MAIN).navigation()
                        finish()
                    }
                }

            } else {
                val s2 = binding.etS2.text.toString().trim()
                val password = binding.etPassword.text.toString().trim()
                val mnem = Walletapi.recoverMnemonic(s1, s2)
                viewModel.loginForMnemonic(mAccount,mnem, s2, password)
            }


        })
    }

    private fun checkAccount(): Boolean {
        if (mAccount.isEmpty()) {
            if (loginType == PHONE_LOGIN) {
                toast(R.string.login_tips_input_phone)
            } else {
                toast(R.string.login_tips_input_email)
            }
            return false
        }
        if (loginType == PHONE_LOGIN) {
            if (mAccount.length != 11) {
                toast(R.string.login_tips_correct_phone)
                return false
            }
        } else {
            if (!mAccount.matches(Patterns.EMAIL_ADDRESS.toRegex())) {
                toast(R.string.login_tips_correct_email)
                return false
            }
        }
        return true
    }

    private fun checkCode(): Boolean {
        if (binding.etCode.text.toString().trim().isEmpty()) {
            toast(R.string.login_tips_input_code)
            return false
        }
        if (binding.etCode.text.toString().trim().length < 6) {
            toast(R.string.login_tips_correct_code)
            return false
        }
        return true
    }

    private fun checkPassword(): Boolean {
        if (binding.etPassword.text.toString().trim().isEmpty()) {
            toast("请输入密码")
            return false
        }
        if (binding.etPassword.text.toString().trim().length !in 8..16) {
            toast("请输入8-16位密码")
            return false
        }
        return true
    }


    private fun checkS2(): Boolean {
        if (binding.etS2.text.toString().trim().isEmpty()) {
            toast(R.string.login_tips_input_s2)
            return false
        }
        return true
    }

    private fun checkServer(): Boolean {
        if (!ServerManager.isOnline()) {
            toast(R.string.chat_login_tips_choose_server)
            return false
        }
        return true
    }
}