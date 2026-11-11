package com.fzm.chat.wallet.ui.transactions

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.TimeUtils
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.biz.base.AppConst.LIVE_KEY_SENDGROUP
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.ui.DeepLinkHelper
import com.fzm.chat.biz.ui.PasswordVerifyFragment
import com.fzm.chat.biz.utils.MoneyInputFilter
import com.fzm.chat.biz.utils.highlightAddress
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.Contact
import com.fzm.chat.core.data.bean.chains
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.app.AppModule
import com.fzm.chat.router.biz.PasswordVerifier
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.data.FROM_WALLET
import com.fzm.chat.wallet.data.format4
import com.fzm.wallet.sdk.utils.decimalPlaces
import com.fzm.chat.wallet.databinding.ActivityTransferBinding
import com.fzm.chat.wallet.ui.WalletViewModel
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.widget.dialog.EasyDialog
import com.jeremyliao.liveeventbus.LiveEventBus
import com.king.zxing.Intents
import com.zjy.architecture.base.instance
import com.zjy.architecture.ext.*
import com.zjy.architecture.util.FinanceUtils
import com.zjy.architecture.util.other.BarUtils
import org.koin.android.ext.android.bind
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * @author zhengjy
 * @since 2021/08/03
 * Description:转账收款页面
 */
@Route(path = WalletModule.TRANSFER)
class TransferActivity : BizActivity() {

    /**
     * 来源
     */
    @JvmField
    @Autowired(name = "from")
    var from: String? = null

    /**
     * 目标
     */
    @JvmField
    @Autowired(name = "target")
    var target: String? = null

    /**
     * 目标
     */
    //item.user?.getDisplayNameAll()
    @JvmField
    @Autowired(name = "displayName")
    var displayName: String? = null

    /**
     * 币种地址
     */
    @JvmField
    @Autowired
    var coinAddress: String? = null

    /**
     * 主链
     */
    @JvmField
    @Autowired
    var chain: String? = null

    /**
     * 平台码（平行链）
     */
    @JvmField
    @Autowired
    var platform: String? = null


    /**
     * 转账收款币种
     */
    @JvmField
    @Autowired
    var symbol: String? = null

    /**
     * 是否需要发送转账消息
     */
    @JvmField
    @Autowired
    var sendMsg: Boolean = true

    @JvmField
    @Autowired
    var sendGroupMsg: Boolean = false

    /**
     * 从聊天页面进入
     */
    @JvmField
    @Autowired
    var fromChat: Boolean = false

    /**
     * 当前选择的币种
     */
    var currentCoin: Coin? = null

    /**
     * 如果是主代币，则最大可用额度需要扣除手续费
     * 如果是Token，则最大可用额度不需要扣除手续费
     */
    private val available: Double
        get() = if (currentCoin != null && currentCoin?.chain == currentCoin?.name) {
            (currentCoin?.balance?.toDouble() ?: 0.0) - fee
        } else {
            (currentCoin?.balance?.toDouble() ?: 0.0)
        }

    private var contact: Contact? = null

    // 界面上填写的信息
    private var amount: Double = 0.0
    private var fee: Double = 0.0
    private var note: String = ""
    private var localNote: String = ""

    private var getFeeFail = false
    private var editAmount = false
    private var isSelf = false
    private var allowChange = true

    private val viewModel by viewModel<WalletViewModel>()
    private val delegate by inject<LoginDelegate>()

    private lateinit var mAdapter: BaseQuickAdapter<Coin, BaseViewHolder>
    private val binding by init { ActivityTransferBinding.inflate(layoutInflater) }

    override fun setSystemBar() {
        notchSupport(window)
        // 状态栏透明，由标题栏背景延伸到状态栏区域（与交易记录页保持一致）
        BarUtils.setStatusBarColor(this, ContextCompat.getColor(this, android.R.color.transparent), 0)
        binding.rlTitle.layoutParams = binding.rlTitle.layoutParams.apply {
            height += BarUtils.getStatusBarHeight(instance)
        }
        BarUtils.addPaddingTopEqualStatusBarHeight(this, binding.rlTitle)
        BarUtils.setStatusBarLightMode(this, true)
        window?.navigationBarColor = ContextCompat.getColor(this, R.color.biz_color_primary)
    }

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
        if (from == FROM_WALLET) {
            binding.llFromContact.visibility = View.GONE
            binding.llGotoOtherAddress.visibility = View.VISIBLE
        } else {
            binding.llFromContact.visibility = View.VISIBLE
            binding.llGotoOtherAddress.visibility = View.GONE
        }
        binding.etAmount.filters = arrayOf(MoneyInputFilter(true))
        if (delegate.getAddress() == target) {
            isSelf = true
            toast(getString(R.string.wallet_cannot_transfer_self))
            binding.root.postDelayed({
                finish()
            }, 1000)
        }
        if (!symbol.isNullOrEmpty()) {
            binding.tvTransferType.text = getString(R.string.wallet_transfer_format, symbol)
        }
        binding.tvSubmit.text = getString(R.string.wallet_confirm_transfer)
        binding.rlRemoteNote.visible()
        binding.drawerContent.rvCoin.layoutManager = LinearLayoutManager(this)
        mAdapter =
            object : BaseQuickAdapter<Coin, BaseViewHolder>(R.layout.item_transfer_assets, null) {
                override fun convert(holder: BaseViewHolder, item: Coin) {
                    holder.getView<ImageView>(R.id.ic_coin).load(item.icon)
                    holder.setText(R.id.tv_coin, item.name)
                    holder.setText(R.id.tv_balance, item.balance)
                }
            }
        mAdapter.setOnItemClickListener { _, _, position ->
            changeSymbol(mAdapter.data[position])
            binding.transferDrawer.closeDrawer(GravityCompat.END)
        }
        binding.drawerContent.rvCoin.adapter = mAdapter
        if (!coinAddress.isNullOrEmpty()) {
            allowChange = false
            binding.tvAddress.text =
                highlightAddress(coinAddress, resources.getColor(R.color.biz_color_accent))
            binding.transferDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.tvTransferType.setCompoundDrawables(null, null, null, null)
//        } else {
//            allowChange = true
//            binding.transferDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
    }

    private var chooseTarget: String? = ""
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        target = intent.getStringExtra("target")
        viewModel.getTargetInfo(target)
    }

    override fun initData() {
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.getContact.observe(this) {
            contact = it
            it?.let {
                binding.ivAvatar.load(it.getDisplayImage(), R.mipmap.default_avatar_round)
                binding.tvName.text = it.getDisplayName()
                currentCoin?.also { asset ->
                    coinAddress = it.chains[asset.chain]
                    chooseTarget = coinAddress
                    if (coinAddress.isNullOrEmpty()) {
                        toast(getString(R.string.wallet_recipient_address_not_found, asset.name))
                    }
                }
                binding.tvAddress.text =
                    highlightAddress(coinAddress, resources.getColor(R.color.biz_color_accent))
                binding.etToAddress.setText(
                    highlightAddress(
                        coinAddress,
                        resources.getColor(R.color.biz_color_accent)
                    )
                )

            }

        }
        if (from != FROM_WALLET) {
            viewModel.getTargetInfo(target)
        }


        viewModel.moduleAssets.observe(this) {
            if (currentCoin == null) {
                if (symbol.isNullOrEmpty()) {
                    // 默认显示列表的第一个币种
                    changeSymbol(it.firstOrNull())
                } else {

                  /*  var cuChain = chain
                    var cuPlatform = platform

                    if (symbol == "BTY") {
                        cuChain = "ETH"
                        cuPlatform = "ethereum"
                    } else {
                        cuChain = chain
                        cuPlatform = platform
                    }*/
                    // 仅传 symbol（如聊天内转账默认 BTY）时按币种名匹配；同时传链/平台则精确匹配
                    val coin = it.find { asset ->
                        asset.name == symbol &&
                            (chain.isNullOrEmpty() || asset.chain == chain) &&
                            (platform.isNullOrEmpty() || asset.platform == platform)
                    } ?: it.firstOrNull()
                    changeSymbol(coin)
                }
            }
            mAdapter.setList(it)
        }
        viewModel.miner.observe(this) {
            if (it != null) {
                fee = it.average.toDouble()

                if (currentCoin?.name == "BTY" && currentCoin?.chain == "ETH") {
                    binding.tvFee.text = "${it.average} BTY"
                } else {
                    if (currentCoin?.platform == "wwchain") {
                        binding.tvFee.text = "${it.average}WW"
                    } else if(currentCoin?.platform == "btyerc"){
                        val cfee = if(currentCoin?.name == "PMM")0.06 else it.average
                        binding.tvFee.text = "$cfee BTY"
                    }else {
                        binding.tvFee.text = "${it.average} ${it.name}"
                    }
                }

                currentCoin?.also { coin ->
                    if (coin.chain != coin.name) {
                        viewModel.checkChainAssetEnough(coin.chain, fee)
                    }
                }
                getFeeFail = false
            } else {
                binding.tvFee.text = "--"
                getFeeFail = true
                toast(getString(R.string.wallet_fee_fetch_fail, currentCoin?.name))
            }
        }
        viewModel.transferResult.observe(this) {
            toast(getString(R.string.wallet_transfer_success))
            finish()
            if (sendMsg) {
                val uri =
                    Uri.parse("${DeepLinkHelper.APP_LINK}?type=chatNotification&address=$target&channelType=${ChatConst.PRIVATE_CHANNEL}")
                ARouter.getInstance().build(AppModule.MAIN)
                    .withParcelable("route", uri)
                    .withFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .navigation()
            }
            if (sendGroupMsg) {
                val timestamp = System.currentTimeMillis()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val date = Date(timestamp)
                val strTime = sdf.format(date)
                val sendValue = "$displayName,$amount$symbol,$strTime"
                LiveEventBus.get<String>(LIVE_KEY_SENDGROUP).post(sendValue)
            }
        }
    }

    private fun changeSymbol(asset: Coin?) {
        if (asset != null) {
            contact?.also {
                coinAddress = it.chains[asset.chain]
                if (coinAddress.isNullOrEmpty()) {
                    toast(getString(R.string.wallet_recipient_address_not_found, asset.name))
                }
            }
            this.currentCoin = asset
            this.symbol = asset.name
            this.chain = asset.chain
            this.platform = asset.platform

            if (currentCoin?.name == "BTY" && currentCoin?.chain == "ETH") {
                viewModel.getRecommendedFee("BTY", asset.name)
            }else if(currentCoin?.platform == "btyerc"){
                viewModel.getRecommendedFee("BTY", asset.name)
            } else  {
                viewModel.getRecommendedFee(asset.chain, asset.name)
            }
            binding.tvTransferType.text = getString(R.string.wallet_transfer_format, symbol)
            val formatValue = format4(asset.balance.toDouble())

            binding.tvBalance.text = getString(R.string.wallet_balance_format, formatValue, symbol)
            binding.tvAddress.text =
                highlightAddress(coinAddress, resources.getColor(R.color.biz_color_accent))
        }
    }

    private fun gotoContarct() {
        currentCoin?.let {
            ARouter.getInstance().build(MainModule.CONTACT_SELECT)
                .withString("symbol", it.name)
                .withString("chain", it.chain)
                .withString("platform", it.platform)
                .withString("action", "transfer")
                .navigation()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if(requestCode == REQUEST_CODE && resultCode == RESULT_OK){
            val result = intent?.getStringExtra(Intents.Scan.RESULT)
            binding.etToAddress.setText(result)
        }

    }
    private val REQUEST_CODE = 1
    override fun setEvent() {
        binding.ivScan.setOnClickListener {
            ARouter.getInstance().build(MainModule.QR_SCAN).withBoolean("forceReturn",true).navigation(this,REQUEST_CODE)
        }
        binding.ivContact.setOnClickListener {
            gotoContarct()
        }

        binding.ivBack.setOnClickListener { finish() }
        binding.tvTransferType.setOnClickListener {
            if (allowChange) {
                binding.transferDrawer.openDrawer(GravityCompat.END)
            }
        }
        binding.tvBalance.setOnClickListener {
            binding.etAmount.setText(currentCoin?.balance)
            binding.etAmount.setSelection(binding.etAmount.text.length)
        }
/*        binding.etAmount.addTextChangedListener(onTextChanged = { s: CharSequence?, start, _, count ->
            if (editAmount) return@addTextChangedListener
            editAmount = true
            var result: CharSequence = ""
            if (!s.isNullOrEmpty()) {
                result = FinanceUtils.formatString(s, currentCoin?.decimalPlaces ?: 2)
                binding.etAmount.setText(result)
                binding.etAmount.setSelection(min(start + count, result.length))
            }
            amount = when {
                result.toString().isEmpty() -> 0.0
                else -> result.toString().toDouble()
            }
            val max = available
            if (amount > max) {
                amount = max
                binding.etAmount.setText(
                    FinanceUtils.getPlainNum(
                        amount,
                        currentCoin?.decimalPlaces ?: 2
                    )
                )
                binding.etAmount.setSelection(binding.etAmount.text.length)
            }
            editAmount = false
        })*/
        binding.etRemoteNote.addTextChangedListener {
            note = it?.toString() ?: ""
        }
        binding.etLocalNote.addTextChangedListener {
            localNote = it?.toString() ?: ""
        }
        binding.remoteNoteTips.setOnClickListener {
            EasyDialog.Builder()
                .setHeaderTitle(getString(R.string.biz_tips))
                .setContent(getString(R.string.wallet_remote_note_tips))
                .setBottomLeftText(null)
                .setBottomRightText(getString(R.string.biz_acknowledge))
                .create(this)
                .show()
        }
        binding.localNoteTips.setOnClickListener {
            EasyDialog.Builder()
                .setHeaderTitle(getString(R.string.biz_tips))
                .setContent(getString(R.string.wallet_local_note_tips))
                .setBottomLeftText(null)
                .setBottomRightText(getString(R.string.biz_acknowledge))
                .create(this)
                .show()
        }
        binding.tvSubmit.setOnClickListener {
            amount = binding.etAmount.text.toString().toDouble()
            if (from == FROM_WALLET) {
                coinAddress = binding.etToAddress.text.toString()
                if (chooseTarget.isNullOrEmpty()) {
                    sendMsg = false
                } else {
                    sendMsg = coinAddress == chooseTarget
                }
            }
            Log.v("tag","sendMsg = $sendMsg coinAddress = $coinAddress chooseTarget = $chooseTarget")
            if (isSelf) {
                toast(getString(R.string.wallet_cannot_transfer_self))
                return@setOnClickListener
            }
            if (from == FROM_WALLET && coinAddress.isNullOrEmpty()) {
                toast(getString(R.string.wallet_hint_input_receive_address))
                return@setOnClickListener
            }
            if (amount == 0.0) {
                toast(getString(R.string.wallet_hint_input_amount))
                return@setOnClickListener
            }
            if (getFeeFail) {
                toast(getString(R.string.wallet_hint_get_fee_first))
                return@setOnClickListener
            }
            currentCoin?.also { coin ->
                if (viewModel.enoughChainAssets.value == false) {
                    toast(getString(R.string.wallet_insufficient_fee, coin.chain))
                    return@also
                }
                if (viewModel.hasPassword()) {
                    val dialog by route<PasswordVerifyFragment>(
                        MainModule.VERIFY_ENC_PWD,
                        bundleOf("type" to 0)
                    )
                    dialog?.setOnPasswordVerifyListener(object :
                        PasswordVerifier.OnPasswordVerifyListener {
                        override fun onSuccess(password: String) {
                            val cfee = if(coin.name == "PMM")0.06 else fee
                            viewModel.transfer(
                                coin,
                                target!!,
                                coinAddress!!,
                                coin.name,
                                amount,
                                cfee,
                                password,
                                note,
                                localNote,
                                sendMsg
                            )
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
                            viewModel.transfer(
                                coin,
                                target!!,
                                coinAddress!!,
                                coin.name,
                                amount,
                                fee,
                                password,
                                note,
                                localNote,
                                sendMsg
                            )
                        }
                    })
                    dialog?.show(supportFragmentManager, "SET_ENC_PWD")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.dismiss()
    }

    override fun onBackPressedSupport() {
        if (binding.transferDrawer.isDrawerOpen(GravityCompat.END)) {
            binding.transferDrawer.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressedSupport()
        }
    }
}