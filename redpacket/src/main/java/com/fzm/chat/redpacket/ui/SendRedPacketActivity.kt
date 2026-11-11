package com.fzm.chat.redpacket.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.widget.addTextChangedListener
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.base.EmptyTabFragment
import com.fzm.chat.biz.ui.PasswordVerifyFragment
import com.fzm.chat.biz.utils.MoneyInputFilter
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.ChatTarget
import com.fzm.chat.redpacket.R
import com.fzm.chat.core.crypto.ECCUtils
import com.fzm.chat.core.crypto.ECCUtils.toBytes
import com.fzm.chat.core.data.platform
import com.fzm.chat.redpacket.databinding.ActivitySendRedPacketBinding
import com.fzm.chat.redpacket.toAsset
import com.fzm.chat.router.biz.PasswordVerifier
import com.fzm.chat.router.biz.ResultReceiver
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.redpacket.ModuleAsset
import com.fzm.chat.router.redpacket.RedPacketModule
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.widget.dialog.EasyDialog
import com.zjy.architecture.base.instance
import com.zjy.architecture.ext.bytes2Hex
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.notchSupport
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.visible
import com.zjy.architecture.util.ArithUtils
import com.zjy.architecture.util.FinanceUtils
import com.zjy.architecture.util.KeyboardUtils
import com.zjy.architecture.util.other.BarUtils
import com.zjy.architecture.widget.LoadingDialog
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * @author zhengjy
 * @since 2021/08/24
 * Description:
 */
@Route(path = RedPacketModule.SEND_RED_PACKET)
class SendRedPacketActivity : BizActivity(), View.OnClickListener {

    companion object {
        val ONE_DAY = TimeUnit.DAYS.toSeconds(1)

        const val MAX_PACKET_NUM = 2000

        /** 选择指定领取人 */
        private const val REQUEST_RECEIVERS = 0x101
    }

    /**
     * 红包类型
     * 0：单人红包
     * 1：拼手气红包
     * 2：固定金额红包
     */
    @JvmField
    @Autowired
    var packetType: Int = -1

    @JvmField
    @Autowired
    var token: ModuleAsset? = null

    /**
     * 红包发送对象
     */
    @JvmField
    @Autowired
    var target: ChatTarget? = null

    /**
     * 是否需要发送红包消息
     */
    @JvmField
    @Autowired
    var sendMsg: Boolean = true

    private var currentToken: ModuleAsset? = null

    private var editAmount = false

    private var amount: Double = 0.0
    private var remark: String = ""
    private var packetNum: Int = 0

    private var publicKey = ""

    /** 指定领取人（群红包白名单，由选择页返回） */
    private val receiverAddresses = mutableListOf<String>()
    private val receiverNames = mutableListOf<String>()

    private var sendDialog: LoadingDialog? = null

    private val viewModel by viewModel<RedPacketViewModel>()

    private val binding by init<ActivitySendRedPacketBinding>()

    override val root: View
        get() = binding.root

    override fun setSystemBar() {
        notchSupport(window)
        // 标题栏为红色渐变顶部色，状态栏透明由标题栏背景延伸上去（与红包详情页 / 记录页一致）
        BarUtils.setStatusBarColor(this, ContextCompat.getColor(this, android.R.color.transparent), 0)
        binding.ctbTitle.layoutParams = binding.ctbTitle.layoutParams.apply {
            height += BarUtils.getStatusBarHeight(instance)
        }
        BarUtils.addPaddingTopEqualStatusBarHeight(this, binding.ctbTitle)
        // 红色标题栏上使用浅色状态栏图标
        BarUtils.setStatusBarLightMode(this, false)
        window?.navigationBarColor = ContextCompat.getColor(this, R.color.biz_color_primary_dark)
    }

    private fun isExcludedRedPacketSymbol(symbol: String?): Boolean {
        if (symbol.isNullOrEmpty()) return false
        val s = symbol.uppercase()
        return s == "ETH" || s == "BNB"
    }

    private fun checkParams() {
        requireNotNull(target) { getString(R.string.red_packet_send_target_empty) }
        if (target!!.channelType == ChatConst.PRIVATE_CHANNEL && packetType != ChatConst.RED_PACKET_SINGLE) {
            toast(getString(R.string.red_packet_send_private_only_single))
            finish()
            return
        }
        if (target!!.channelType == ChatConst.GROUP_CHANNEL && packetType == ChatConst.RED_PACKET_SINGLE) {
            toast(getString(R.string.red_packet_send_group_no_single))
            finish()
            return
        }
    }

    override fun initView() {
        ARouter.getInstance().inject(this)
        binding.etAmount.filters = arrayOf(MoneyInputFilter(true))
        binding.etPacketNum.filters = arrayOf(MoneyInputFilter(false))
        checkParams()
        if (isExcludedRedPacketSymbol(token?.symbol)) {
            token = null
        } else {
            changeToken(token)
        }
        if (packetType == -1) {
            packetType = if (target!!.channelType == ChatConst.PRIVATE_CHANNEL) {
                ChatConst.RED_PACKET_SINGLE
            } else {
                ChatConst.RED_PACKET_LUCKY
            }
        }
        when (packetType) {
            ChatConst.RED_PACKET_SINGLE -> {
                binding.tvGroupNum.gone()
                binding.slPacketNum.gone()
                binding.ivPacketType.gone()

                binding.tvAmountTips.text = getString(R.string.red_packet_single_amount)
            }
            ChatConst.RED_PACKET_LUCKY -> {
                binding.tvGroupNum.visible()
                binding.slPacketNum.visible()
                binding.ivPacketType.visible()

                binding.tvAmountTips.text = getString(R.string.red_packet_total_amount)
            }

            ChatConst.RED_PACKET_FAIR -> {
                binding.tvGroupNum.visible()
                binding.slPacketNum.visible()
                binding.ivPacketType.visible()

                binding.tvAmountTips.text = getString(R.string.red_packet_total_amount)
            }
        }
        val showNFT = false
        if (showNFT) {
            binding.packetDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            val token by route<Fragment>(
                WalletModule.DRAWER_ASSETS,
                // 暂时不能发NFT红包
                bundleOf(
                    "showNFT" to showNFT,
                    "hideEthAndBnb" to true
                )
            ) {
                EmptyTabFragment.create()
            }
            (token as? ResultReceiver<Coin>?)?.setOnResultReceiver {
                changeToken(it.toAsset())
                binding.packetDrawer.closeDrawer(GravityCompat.END)
            }
            supportFragmentManager.commitNow { add(R.id.fcv_container, token!!) }
        } else {
            binding.packetDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            val token by route<Fragment>(
                WalletModule.DRAWER_ASSETS,
                // 暂时不能发NFT红包
                bundleOf(
                    "showNFT" to showNFT,
                    "hideEthAndBnb" to true
                )
            ) {
                EmptyTabFragment.create()
            }
            (token as? ResultReceiver<Coin>?)?.setOnResultReceiver {
                changeToken(it.toAsset())
                binding.packetDrawer.closeDrawer(GravityCompat.END)
            }
            supportFragmentManager.commitNow { add(R.id.fcv_container, token!!) }
        }

        // 仅群红包展示「指定领取人」
        updateReceiverRow()
        checkSendEnabled()
    }

    override fun initData() {
        viewModel.loading.observe(this) { setupLoading(it) }
        if (target!!.channelType == ChatConst.GROUP_CHANNEL) {
            viewModel.fetchGroupUsers(target!!.targetId.toLong()).observe(this) { users ->
                binding.tvGroupNum.text = getString(R.string.red_packet_group_member_count, users.size)
            }
        }
        viewModel.packetAsset.observe(this) {
            val allowed = it.filter { asset -> !isExcludedRedPacketSymbol(asset.symbol) }
            if (allowed.isEmpty()) return@observe
            if (token == null) {
                changeToken(allowed.firstOrNull())
            }
        }
        viewModel.sendResult.observe(this) {
            finish()
        }
        viewModel.sendError.observe(this) {
            toast(it.message ?: getString(R.string.red_packet_send_fail))
        }
        viewModel.sendState.observe(this) { state ->
            when (state) {
                RedPacketViewModel.SEND_UI_PREPARE,
                RedPacketViewModel.SEND_UI_VALIDATE_AMOUNT,
                RedPacketViewModel.SEND_UI_CHECK_ALLOWANCE,
                RedPacketViewModel.SEND_UI_APPROVE_WAIT,
                RedPacketViewModel.SEND_UI_CREATE_TX -> {
                    val tips = when (state) {
                        RedPacketViewModel.SEND_UI_PREPARE ->
                            getString(R.string.red_packet_loading_connect_wallet)
                        RedPacketViewModel.SEND_UI_VALIDATE_AMOUNT ->
                            getString(R.string.red_packet_loading_verify_amount)
                        RedPacketViewModel.SEND_UI_CHECK_ALLOWANCE ->
                            getString(R.string.red_packet_loading_check_approve)
                        RedPacketViewModel.SEND_UI_APPROVE_WAIT ->
                            getString(R.string.red_packet_loading_approve_token)
                        RedPacketViewModel.SEND_UI_CREATE_TX ->
                            getString(R.string.red_packet_loading_sign_send)
                        else -> ""
                    }
                    if (state == RedPacketViewModel.SEND_UI_PREPARE) {
                        sendDialog?.dismiss()
                        LoadingDialog(this, false)
                            .setTipsText(tips)
                            .also { sendDialog = it }
                            .show()
                    } else {
                        if (sendDialog == null) {
                            LoadingDialog(this, false)
                                .setTipsText(tips)
                                .also { sendDialog = it }
                                .show()
                        } else {
                            sendDialog?.setTipsText(tips)
                        }
                    }
                }
                RedPacketViewModel.SEND_UI_WAIT_RECEIPT ->
                    sendDialog?.setTipsText(getString(R.string.red_packet_loading_wait_confirm))
                RedPacketViewModel.SEND_UI_SEND_MSG ->
                    sendDialog?.setTipsText(getString(R.string.red_packet_loading_send_msg))
                RedPacketViewModel.SEND_UI_DONE -> sendDialog?.dismiss()
            }
        }

        viewModel.getRedPacketAssets()
    }

    @SuppressLint("SetTextI18n")
    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.ctbTitle.setOnRightClickListener {
            ARouter.getInstance().build(RedPacketModule.RED_PACKET_RECORD).navigation()
        }
        binding.tvSelectedToken.setOnClickListener(this)
        // 「数字资产」整行都可点（以前只有右侧箭头区域可点）
        binding.rlAsset.setOnClickListener(this)
        binding.ivPacketType.setOnClickListener(this)
        binding.tvAmountTips.setOnClickListener(this)
        binding.ivRemarkHelp.setOnClickListener(this)
        binding.sendRedPacket.setOnClickListener(this)
        binding.llReceivers.setOnClickListener(this)
        binding.packetDrawer.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                
            }

            override fun onDrawerOpened(drawerView: View) {

            }

            override fun onDrawerClosed(drawerView: View) {
                
            }

            override fun onDrawerStateChanged(newState: Int) {
                
            }
        })
        binding.etAmount.addTextChangedListener(onTextChanged = { s: CharSequence?, start, _, count ->
            if (editAmount) return@addTextChangedListener
            editAmount = true
            var result: CharSequence = ""
            if (!s.isNullOrEmpty()) {
                result = FinanceUtils.formatString(s, currentToken?.decimalPlaces ?: 2)
                binding.etAmount.setText(result)
                binding.etAmount.setSelection(min(start + count, result.length))
            }
            amount = when {
                result.toString().isEmpty() -> 0.0
                else -> result.toString().toDouble()
            }
            val available = currentToken?.balance?.toDouble() ?: 0.0
            if (amount > available) {
                amount = available
                binding.etAmount.setText(FinanceUtils.getPlainNum(amount, currentToken?.decimalPlaces ?: 2))
                binding.etAmount.setSelection(binding.etAmount.text.length)
            }
            binding.tvTotal.text = FinanceUtils.getPlainNum(amount, currentToken?.decimalPlaces ?: 2)
            checkSendEnabled()
            editAmount = false
        })
        binding.etPacketNum.addTextChangedListener {
            val text = it?.toString() ?: ""
            packetNum = if (text.isEmpty()) 0 else text.toInt()
            checkSendEnabled()
        }
        binding.etRemark.addTextChangedListener {
            val text = it?.toString() ?: ""
            binding.tvRemarkCount.text = "${text.length}/20"
            remark = text
            checkSendEnabled()
        }
    }

    private fun changeToken(token: ModuleAsset?) {
        if (token == null) return
        currentToken = token
        binding.etAmount.setText("")
        binding.etPacketNum.setText("")
        token.apply {
            binding.tvSelectedToken.text = symbol
            binding.tvTokenName.text = symbol
            binding.tvToken.text = symbol

            binding.tvBalance.text =
                getString(R.string.red_packet_available_balance, formatBalanceFourDecimals(balance), symbol)

            binding.tvTotal.text = FinanceUtils.getPlainNum(amount, currentToken?.decimalPlaces ?: 2)
        }
    }

    /**
     * 切换普通红包和拼手气红包
     */
    private fun switchPacketType() {
        if (packetType == ChatConst.RED_PACKET_LUCKY) {
            packetType = ChatConst.RED_PACKET_FAIR
            binding.ivPacketType.setImageResource(R.mipmap.icon_packet_type_fair)
            val drawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_help, null)?.apply {
                setBounds(0, 0, minimumWidth, minimumHeight)
            }
            binding.tvAmountTips.setCompoundDrawables(null, null, drawable, null)
        } else if (packetType == ChatConst.RED_PACKET_FAIR) {
            packetType = ChatConst.RED_PACKET_LUCKY
            binding.ivPacketType.setImageResource(R.mipmap.icon_packet_type_lucky)
            binding.tvAmountTips.setCompoundDrawables(null, null, null, null)
        }
    }

    private fun checkSendEnabled() {
        binding.sendRedPacket.isEnabled = when (packetType) {
            ChatConst.RED_PACKET_SINGLE -> amount > 0
            else -> amount > 0 && binding.etPacketNum.text.isNotEmpty()
        }
    }

    override fun onBackPressedSupport() {
        if (binding.packetDrawer.isDrawerOpen(GravityCompat.END)) {
            binding.packetDrawer.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressedSupport()
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.tv_selected_token, R.id.rl_asset -> {
                binding.packetDrawer.openDrawer(GravityCompat.END)
                KeyboardUtils.hideKeyboard(v)
            }
            R.id.iv_packet_type -> {
                switchPacketType()
            }
            R.id.tv_amount_tips -> {
                if (packetType == ChatConst.RED_PACKET_FAIR) {
                    EasyDialog.Builder()
                        .setHeaderTitle(getString(R.string.biz_tips))
                        .setContent(getString(R.string.red_packet_total_amount_tips))
                        .setBottomLeftText(null)
                        .setBottomRightText(getString(R.string.biz_acknowledge))
                        .create(this)
                        .show()
                }
            }
            R.id.iv_remark_help -> {
                EasyDialog.Builder()
                    .setHeaderTitle(getString(R.string.biz_tips))
                    .setContent(getString(R.string.red_packet_remote_remark_tips))
                    .setBottomLeftText(null)
                    .setBottomRightText(getString(R.string.biz_acknowledge))
                    .create(this)
                    .show()
            }
            R.id.send_red_packet -> {
                if (packetType != ChatConst.RED_PACKET_SINGLE) {
                    if (packetNum > MAX_PACKET_NUM) {
                        toast(getString(R.string.red_packet_count_exceed, MAX_PACKET_NUM))
                        return
                    }
                    val single = ArithUtils.div(amount, packetNum.toDouble())
                    val min = 10.0.pow(-(currentToken?.decimalPlaces?.toDouble() ?: 0.0))
                    if (single < min) {
                        toast(getString(R.string.red_packet_amount_below_min))
                        return
                    }
                }
                val keyPair = ECCUtils.genECKeyPair()
                val ecPrivKey = keyPair.private.toBytes().bytes2Hex()
                publicKey = keyPair.public.toBytes().bytes2Hex()
                val count = if (packetType == ChatConst.RED_PACKET_SINGLE) 1 else packetNum
                val finalRemark = remark.ifEmpty { getString(R.string.red_packet_default_remark) }
                val expire = System.currentTimeMillis() / 1000 + ONE_DAY

                if (receiverAddresses.isEmpty()) {
                    verifyAndSend(ecPrivKey, count, finalRemark, expire, emptyList())
                } else {
                    // 指定领取人：先解析成员钱包收款地址（合约白名单，只有这些地址能领取），再走密码校验
                    val loading = LoadingDialog(this, false)
                        .setTipsText(getString(R.string.red_packet_loading_receivers))
                        .also { it.show() }
                    lifecycleScope.launch {
                        val result = try {
                            viewModel.resolveReceiverWhitelist(receiverAddresses)
                        } catch (e: Exception) {
                            null
                        }
                        loading.dismiss()
                        when {
                            result == null ->
                                toast(getString(R.string.red_packet_receivers_resolve_fail))
                            result.missingNames.isNotEmpty() -> toast(
                                getString(
                                    R.string.red_packet_receivers_missing_address,
                                    result.missingNames.joinToString("、")
                                )
                            )
                            else -> verifyAndSend(
                                ecPrivKey, count, finalRemark, expire, result.addresses
                            )
                        }
                    }
                }
            }
            R.id.ll_receivers -> openReceiverPicker()
        }
    }

    /**
     * 打开「指定领取人」选择页（群成员多选，可按昵称/地址搜索）
     */
    private fun openReceiverPicker() {
        val chatTarget = target ?: return
        if (chatTarget.channelType != ChatConst.GROUP_CHANNEL) return
        startActivityForResult(
            Intent(this, RedPacketReceiverActivity::class.java)
                .putExtra(
                    RedPacketReceiverActivity.EXTRA_GROUP_ID,
                    chatTarget.targetId.toLongOrNull() ?: 0L
                )
                .putStringArrayListExtra(
                    RedPacketReceiverActivity.EXTRA_SELECTED,
                    ArrayList(receiverAddresses)
                ),
            REQUEST_RECEIVERS
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RECEIVERS && resultCode == RESULT_OK) {
            receiverAddresses.clear()
            receiverAddresses.addAll(
                data?.getStringArrayListExtra(RedPacketReceiverActivity.EXTRA_ADDRESSES).orEmpty()
            )
            receiverNames.clear()
            receiverNames.addAll(
                data?.getStringArrayListExtra(RedPacketReceiverActivity.EXTRA_NAMES).orEmpty()
            )
            updateReceiverRow()
        }
    }

    /**
     * 刷新「指定领取人」一行：默认所有人可领，已选时显示人数
     */
    private fun updateReceiverRow() {
        val group = target?.channelType == ChatConst.GROUP_CHANNEL &&
            packetType != ChatConst.RED_PACKET_SINGLE
        if (group) binding.llReceivers.visible() else binding.llReceivers.gone()
        binding.tvReceivers.text = if (receiverAddresses.isEmpty()) {
            getString(R.string.red_packet_receivers_all)
        } else {
            getString(R.string.red_packet_receivers_count, receiverAddresses.size)
        }
    }

    /**
     * 密码校验后发送红包
     *
     * @param allowedAddresses 指定领取人白名单（0x 收款地址，空表示所有人可领）
     */
    private fun verifyAndSend(
        ecPrivKey: String,
        count: Int,
        finalRemark: String,
        expire: Long,
        allowedAddresses: List<String>
    ) {
        val sendAction: (String) -> Unit = { password ->
            viewModel.sendRedPacketByContract(
                password = password,
                amount = amount,
                packetCount = count,
                packetType = packetType,
                tokenAddress = currentToken?.contractAddress ?: "",
                remark = finalRemark,
                target = target!!,
                moduleAsset = currentToken,
                ecPrivateKey = ecPrivKey,
                expireAt = expire,
                allowedAddresses = allowedAddresses
            )
        }

        // 发红包每次都要求输入密码（只有领红包才复用内存里的密码）
        if (viewModel.hasPassword()) {
            val dialog by route<PasswordVerifyFragment>(
                MainModule.VERIFY_ENC_PWD,
                bundleOf("type" to 0)
            )
            dialog?.setOnPasswordVerifyListener(object :
                PasswordVerifier.OnPasswordVerifyListener {
                override fun onSuccess(password: String) {
                    sendAction(password)
                }
            })
            dialog?.show(supportFragmentManager, "VERIFY_ENC_PWD")
        } else {
            val dialog by route<PasswordVerifyFragment>(
                MainModule.SET_ENC_PWD,
                bundleOf("showWords" to 0)
            )
            dialog?.setOnPasswordVerifyListener(object :
                PasswordVerifier.OnPasswordVerifyListener {
                override fun onSuccess(password: String) {
                    sendAction(password)
                }
            })
            dialog?.show(supportFragmentManager, "SET_ENC_PWD")
        }
    }

    /** 可用余额展示：固定 4 位小数（与资产详情余额展示一致） */
    private fun formatBalanceFourDecimals(balanceStr: String): String {
        val format = DecimalFormat("0.0000", DecimalFormatSymbols(Locale.US))
        format.roundingMode = RoundingMode.FLOOR
        return format.format(balanceStr.toDoubleOrNull() ?: 0.0)
    }
}