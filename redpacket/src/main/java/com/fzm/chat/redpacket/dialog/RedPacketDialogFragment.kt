package com.fzm.chat.redpacket.dialog

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.ui.PasswordVerifyFragment
import com.fzm.chat.core.crypto.ECCUtils
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.session.SessionKeyCache
import com.fzm.chat.redpacket.R
import com.fzm.chat.redpacket.data.bean.ReceiveRedPacketParams
import com.fzm.chat.redpacket.databinding.DialogRedEnvelopeBinding
import com.fzm.chat.redpacket.evm.RedPacketEvmContract
import com.fzm.chat.redpacket.exception.RedPacketStateException
import com.fzm.chat.redpacket.ui.RedPacketViewModel
import com.fzm.chat.router.biz.PasswordVerifier
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.redpacket.RedPacketModule
import com.fzm.chat.router.route
import com.zjy.architecture.ext.*
import com.zjy.architecture.widget.LoadingDialog
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2021/09/02
 * Description:
 */
@Route(path = RedPacketModule.PACKET_DIALOG)
class RedPacketDialogFragment : DialogFragment() {

    @JvmField
    @Autowired
    var packetId: String? = null

    @JvmField
    @Autowired
    var message: ChatMessage? = null

    private var receiveDialog: LoadingDialog? = null

    private val viewModel by viewModel<RedPacketViewModel>()

    private lateinit var binding: DialogRedEnvelopeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ARouter.getInstance().inject(this)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.attributes = window?.attributes?.apply {
                gravity = Gravity.CENTER
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            binding = DialogRedEnvelopeBinding.inflate(layoutInflater)
            window?.setContentView(binding.root)
            binding.remark.text = message?.msg?.remark
            binding.ivHead.load(message?.sender?.getDisplayImage(), R.mipmap.default_avatar_round)
            val name = message?.sender?.getDisplayName()?:""
            binding.tvName.text = if (name.length > 8) name.substring(0, 7) + "…" else name
            binding.coinType.text = "发了一个${message?.msg?.symbol}红包"
            binding.open.singleClick {
                message?.apply {
                    val msgRef = this
                    val packetIdStr = packetId!!
                    if (RedPacketEvmContract.isEnabled()) {
                        val claim: (String) -> Unit = { pwd ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                viewModel.receiveRedPacketByContract(pwd, packetIdStr, msgRef)
                            } else {
                                toast("链上红包需要 Android 7.0 及以上系统")
                            }
                        }
                        // 会话内输过密码就直接复用（只存内存，App 被杀后需重新输入）
                        val address = viewModel.delegate.getAddress()
                        val cachedPwd = SessionKeyCache.get(address)
                        if (cachedPwd != null) {
                            claim(cachedPwd)
                            return@apply
                        }
                        if (viewModel.hasPassword()) {
                            val pwdDialog by route<PasswordVerifyFragment>(
                                MainModule.VERIFY_ENC_PWD,
                                bundleOf("type" to 0)
                            )
                            pwdDialog?.setOnPasswordVerifyListener(object :
                                PasswordVerifier.OnPasswordVerifyListener {
                                override fun onSuccess(password: String) {
                                    SessionKeyCache.put(address, password)
                                    claim(password)
                                }
                            })
                            pwdDialog?.show(parentFragmentManager, "VERIFY_ENC_PWD")
                        } else {
                            val pwdDialog by route<PasswordVerifyFragment>(
                                MainModule.SET_ENC_PWD,
                                bundleOf("showWords" to 0)
                            )
                            pwdDialog?.setOnPasswordVerifyListener(object :
                                PasswordVerifier.OnPasswordVerifyListener {
                                override fun onSuccess(password: String) {
                                    SessionKeyCache.put(address, password)
                                    claim(password)
                                }
                            })
                            pwdDialog?.show(parentFragmentManager, "SET_ENC_PWD")
                        }
                    } else {
                        val address = viewModel.delegate.getAddress() ?: ""
                        val signature = ECCUtils.btcCoinSign(
                            address.toByteArray(),
                            msg.privateKey!!.hex2Bytes()
                        )
                        val sign = ReceiveRedPacketParams.RedPacketSign(signature.bytes2Hex())
                        val params = ReceiveRedPacketParams(packetIdStr, sign, msg.exec!!)
                        viewModel.receiveRedPacket(params, this)
                    }
                }
            }
        }
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.receiveError.observe(this) {
            dismiss()
            toast(it.message ?: "未知错误")
            if (it is RedPacketStateException) {
                ARouter.getInstance().build(RedPacketModule.RED_PACKET_DETAIL)
                    .withString("packetId", packetId)
                    .navigation()
            } else {

            }
        }
        viewModel.receiveState.observe(this) { state ->
            when (state) {
                1 -> {
                    receiveDialog?.dismiss()
                    LoadingDialog(requireContext(), false)
                        .setTipsText("查询中…")
                        .also { receiveDialog = it }
                        .show()
                }
                2 -> receiveDialog?.setTipsText("红包领取中…")
                3 -> receiveDialog?.setTipsText("链上领取中…")
                4 -> receiveDialog?.setTipsText("资产转移中…")
                5 -> receiveDialog?.setTipsText("上链中…")
                6 -> receiveDialog?.dismiss()
            }
        }
        viewModel.receiveResult.observe(this) {
            dismiss()
            ARouter.getInstance().build(RedPacketModule.RED_PACKET_DETAIL)
                .withString("packetId", it)
                .navigation()
        }
        return dialog
    }
}