package com.fzm.chat.security

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.base.shareCode
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.utils.MMkvUtil
import com.fzm.chat.databinding.ActivitySecuritySettingBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.fzm.chat.vm.BackupViewModel
import com.fzm.chat.vm.SecuritySettingViewModel
import com.fzm.chat.widget.ExportWordsDialog
import com.google.android.material.textfield.TextInputEditText
import com.king.zxing.util.CodeUtils
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.litepal.LitePal
import org.litepal.extension.find

/**
 * @author zhengjy
 * @since 2020/12/16
 * Description:
 */
@Route(path = MainModule.SECURITY_SET)
class SecuritySettingActivity : BizActivity(), View.OnClickListener {
    private val backViewModel by viewModel<BackupViewModel>()
    private val loginDelegate by inject<LoginDelegate>()
    private val walletService by route<WalletService>(WalletModule.SERVICE)

    companion object {
        const val BIND_PHONE = 100
        const val BIND_EMAIL = 200
    }

    @JvmField
    @Autowired
    var bindType = 0

    private val viewModel by viewModel<SecuritySettingViewModel>()
    private var phone = ""
    private var email = ""

    private val binding by init { ActivitySecuritySettingBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    override val darkStatusColor: Boolean = true

    override fun initView() {
        ARouter.getInstance().inject(this)
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.refresh.observe(this) { refreshState() }
        viewModel.current.observe(this) {
            if (it.isLogin()) {
                phone = it.phone ?: ""
                email = it.email ?: ""
                refreshBindInfo()
            } else {
                phone = ""
                email = ""
            }
        }
        when (bindType) {
            ChatConst.PHONE -> root.post { binding.llPhone.performClick() }
            ChatConst.EMAIL -> root.post { binding.llEmail.performClick() }
        }

        if (W.appType == WConfig.APP_NCHAT) {
            binding.llMnem.visibility = View.GONE
        }
    }

    private var myAddress = ""
    override fun initData() {
        loginDelegate.current.observe(this) {
            if (it.isLogin()) {
                myAddress = it.address
                backViewModel.queryAccounts(myAddress)
                // 展示上次真实同步结果，避免永远显示“点击同步账户地址”造成“从未同步”的误解
                val syncKey = "${ChatConst.UserField.CHAIN_SYNC_PREFIX}${it.address}"
                if (MMkvUtil.containsKey(syncKey)) {
                    binding.tvChainSyncValue.setText(
                        if (MMkvUtil.decodeBoolean(syncKey)) R.string.chat_chain_sync_success
                        else R.string.chat_chain_sync_failed
                    )
                }
            }
        }

    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.llMnem.setOnClickListener(this)
        binding.llS2.setOnClickListener(this)
        binding.llPhone.setOnClickListener(this)
        binding.llWechat.setOnClickListener(this)
        binding.llXhs.setOnClickListener(this)
        binding.llDouyin.setOnClickListener(this)
        binding.llEmail.setOnClickListener(this)
        binding.llEncryptedPassword.setOnClickListener(this)
        binding.llChainSync.setOnClickListener(this)
        backViewModel.bindAccount.observe(this, Observer {
            backViewModel.queryAccounts(myAddress)
        })

        backViewModel.queryAccounts.observe(this, Observer { bindAccount ->
            binding.tvPhoneValue.text = "${bindAccount.phone}"
            binding.tvWechatValue.text = "${bindAccount.wechat_id}"
            binding.tvXhsValue.text = "${bindAccount.xiaohongshu_id}"
            binding.tvDouyinValue.text = "${bindAccount.douyin_id}"

        })
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        if (viewModel.preference.hasChatPassword()) {
            binding.tvEncryptedPasswordTips.setText(R.string.chat_tips_action1)
        } else {
            binding.tvEncryptedPasswordTips.setText(R.string.chat_tips_action2)
        }
    }

    private fun refreshBindInfo() {
//        if (phone.isEmpty()) {
//            binding.tvPhoneTips.setText(R.string.chat_tips_action3)
//        } else {
//            binding.tvPhoneTips.text = phone
//        }
//        if (email.isEmpty()) {
//            binding.tvEmailTips.setText(R.string.chat_tips_action3)
//        } else {
//            binding.tvEmailTips.text = email
//        }
    }

    /**
     * 手动同步收款地址（链上资料 chain.* 字段），并展示结果；失败时给出本机 vs 链上差异诊断
     */
    private fun startChainSync() {
        binding.tvChainSyncValue.text = getString(R.string.chat_chain_sync_pending)
        lifecycleScope.launch {
            val ret = runCatching { walletService?.updateChainAddress(null) }.getOrNull()
            when (ret) {
                "ok" -> {
                    binding.tvChainSyncValue.text = getString(R.string.chat_chain_sync_success)
                    toast(R.string.chat_chain_sync_success)
                }
                "local_empty" -> {
                    // 本地钱包未就绪（暂无可同步的链地址），稍后再试
                    binding.tvChainSyncValue.text = getString(R.string.chat_chain_sync_failed)
                    toast(R.string.chat_chain_sync_fail)
                }
                else -> {
                    binding.tvChainSyncValue.text = getString(R.string.chat_chain_sync_failed)
                    val diff = runCatching { walletService?.queryChainAddressSyncState() }.getOrNull()
                    if (diff.isNullOrEmpty()) {
                        toast(R.string.chat_chain_sync_fail)
                    } else {
                        toast(diff)
                    }
                }
            }
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.ll_chain_sync -> startChainSync()
            R.id.ll_mnem -> {
                val id = MyWallet.getID()
                val dialog by route<DialogFragment>(
                    MainModule.VERIFY_ENC_PWD, bundleOf("type" to 1, "waletId" to id)
                )
                dialog?.show(supportFragmentManager, "VERIFY_ENC_PWD1")
            }
//            R.id.ll_private -> {
//                if (viewModel.preference.hasChatPassword()) {
//                    val dialog by route<DialogFragment>(
//                        MainModule.VERIFY_ENC_PWD,
//                        bundleOf("type" to 2)
//                    )
//                    dialog?.show(supportFragmentManager, "VERIFY_ENC_PWD2")
//                } else {
//                    val dialog by route<DialogFragment>(
//                        MainModule.SET_ENC_PWD,
//                        bundleOf("showWords" to 2)
//                    )
//                    dialog?.show(supportFragmentManager, "SET_ENC_PWD2")
//                }
//            }
            R.id.ll_phone -> {
//                if (phone.isEmpty()) {
//                    if (viewModel.preference.hasChatPassword()) {
//                        bindPhone()
//                    } else {
//                        applicationContext?.toast(R.string.chat_set_chat_password)
//                        ARouter.getInstance().build(MainModule.ENCRYPT_PWD)
//                            .withBoolean("setMode", true)
//                            .navigation(this, BIND_PHONE)
//                    }
//                }


                showInputDialog(BIND_PHONE)

            }

            R.id.ll_wechat -> {
                showInputDialog(BIND_WECHAT)

            }

            R.id.ll_xhs -> {
                showInputDialog(BIND_XHS)

            }

            R.id.ll_douyin -> {
                showInputDialog(BIND_DOUYIN)

            }

            R.id.ll_email -> {
//                if (email.isEmpty()) {
//                    if (viewModel.preference.hasChatPassword()) {
//                        bindEmail()
//                    } else {
//                        applicationContext?.toast(R.string.chat_set_chat_password)
//                        ARouter.getInstance().build(MainModule.ENCRYPT_PWD)
//                            .withBoolean("setMode", true)
//                            .navigation(this, BIND_EMAIL)
//                    }
//                }
            }

            R.id.ll_encrypted_password -> {
                ARouter.getInstance().build(MainModule.ENCRYPT_PWD).withBoolean("setMode", false)
                    .navigation()
            }

            R.id.ll_s2 -> {
                val id = MyWallet.getID()
                val pWallet = LitePal.find<com.fzm.wallet.sdk.db.entity.PWallet>(id)
                pWallet?.let {
                    ExportWordsDialog(this, it.user, 3).show()
                }

            }
        }
    }

    private fun bindPhone() {
        ARouter.getInstance().build(MainModule.BIND_ACCOUNT).withInt("bindType", ChatConst.PHONE)
            .navigation()
    }

    private fun bindEmail() {
        ARouter.getInstance().build(MainModule.BIND_ACCOUNT).withInt("bindType", ChatConst.EMAIL)
            .navigation()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            if (requestCode == BIND_PHONE) {
                bindPhone()
            } else if (requestCode == BIND_EMAIL) {
                bindEmail()
            }
        }
    }

    val BIND_PHONE = 1
    val BIND_WECHAT = 2
    val BIND_XHS = 3
    val BIND_DOUYIN = 4

    private fun showInputDialog(type: Int) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_binds, null)
        val tvHint: TextView = dialogView.findViewById(R.id.tv_hint)
        val etInput: EditText = dialogView.findViewById(R.id.et_input)
        val btnConfirm: Button = dialogView.findViewById(R.id.btn_confirm)

        val unbindText = getString(R.string.chat_tips_action3)
        when (type) {
            BIND_PHONE -> {
                tvHint.text = "请输入手机号"
                val current = binding.tvPhoneValue.text.toString().trim()
                if (current.isNotEmpty() && current != unbindText) {
                    etInput.setText(current)
                    etInput.setSelection(current.length)
                }
            }

            BIND_WECHAT -> {
                tvHint.text = "请输入微信号"
                val current = binding.tvWechatValue.text.toString().trim()
                if (current.isNotEmpty() && current != unbindText) {
                    etInput.setText(current)
                    etInput.setSelection(current.length)
                }
            }

            BIND_XHS -> {
                tvHint.text = "请输入小红书号"
                val current = binding.tvXhsValue.text.toString().trim()
                if (current.isNotEmpty() && current != unbindText) {
                    etInput.setText(current)
                    etInput.setSelection(current.length)
                }
            }

            BIND_DOUYIN -> {
                tvHint.text = "请输入抖音号"
                val current = binding.tvDouyinValue.text.toString().trim()
                if (current.isNotEmpty() && current != unbindText) {
                    etInput.setText(current)
                    etInput.setSelection(current.length)
                }
            }
        }
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        // 去除弹窗默认的背景（避免圆角被覆盖）
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnConfirm.setOnClickListener {
            val input = etInput.text.toString().trim()
            if (input.isNotEmpty()) {
                when (type) {
                    BIND_PHONE -> {
                        backViewModel.bindAccount(input, "", "", "", myAddress)
                    }

                    BIND_WECHAT -> {
                        backViewModel.bindAccount("", input, "", "", myAddress)
                    }

                    BIND_XHS -> {
                        backViewModel.bindAccount("", "", input, "", myAddress)
                    }

                    BIND_DOUYIN -> {
                        backViewModel.bindAccount("", "", "", input, myAddress)
                    }
                }
                dialog.dismiss()
            }

        }

        // 5. 显示弹窗
        dialog.show()
    }
}