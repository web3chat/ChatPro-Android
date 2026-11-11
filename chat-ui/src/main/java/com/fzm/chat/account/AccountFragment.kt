package com.fzm.chat.account

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.app.ActivityOptionsCompat
import androidx.core.util.Pair
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.bean.mnem.AccountMenuItem
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.base.FunctionModules
import com.fzm.chat.biz.base.enableLiveLive
import com.fzm.chat.biz.base.enableRedPacketLive
import com.fzm.chat.biz.base.shareCode
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.FragmentAccountBinding
import com.fzm.chat.router.live.LiveModule
import com.fzm.chat.router.live.LiveService
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.main.MainService
import com.fzm.chat.router.oa.OAModule
import com.fzm.chat.router.oa.OAService
import com.fzm.chat.router.redpacket.RedPacketModule
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.fzm.chat.ui.EditAvatarActivity
import com.fzm.chat.ui.QRCodeActivity
import com.fzm.chat.utils.AccountProfileCache
import com.fzm.chat.utils.addrIsContained
import com.fzm.chat.widget.HomeActionPopup
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.widget.dialog.EasyDialog
import com.king.zxing.util.CodeUtils
import com.zjy.architecture.ext.checkSingle
import com.zjy.architecture.ext.clipboardManager
import com.zjy.architecture.ext.dp
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.setVisible
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.versionName
import com.zjy.architecture.util.other.BarUtils
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.litepal.LitePal
import org.litepal.extension.deleteAll
import org.litepal.extension.find

/**
 * @author zhengjy
 * @since 2020/12/14
 * Description:
 */
@Route(path = MainModule.ACCOUNT)
class AccountFragment : BizFragment(), View.OnClickListener {

    private val loginDelegate by inject<LoginDelegate>()
    private val mainService by route<MainService>(MainModule.SERVICE)
    private val oaService by route<OAService>(OAModule.SERVICE)
    private val walletService by route<WalletService>(WalletModule.SERVICE)
    private val liveService by route<LiveService>(LiveModule.SERVICE)

    private val binding by init<FragmentAccountBinding>()

    private lateinit var mAdapter: BaseQuickAdapter<AccountMenuItem, BaseViewHolder>
    private var content = ""

//    private val walletModule by lazy {
//        AccountMenuItem(
//            R.mipmap.icon_my_wallet,
//            getString(R.string.chat_account_menu_wallet),
//            "",
//            false,
//            WalletModule.WALLET_INDEX to null
//        )
//    }

    private val redPacketModule by lazy {
        AccountMenuItem(
            R.mipmap.icon_my_red_packet,
            getString(R.string.chat_account_menu_red_packet),
            "",
            false,
            RedPacketModule.RED_PACKET_RECORD to null
        )
    }

    private val viewModel by viewModel<BusinessViewModel>()

    private var titleAlpha = 0f

    private var avatar = ""
    private var nickname = ""

    /** 距上次“我的”页收款地址补同步的时间戳（毫秒），防止频繁切换页签时重复提交 */
    private var lastChainSyncAttempt = 0L
    /** “我的”页收款地址补同步的最小间隔 */
    private val chainSyncMinInterval = 30 * 1000L

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {

//       try {
//           val id = MyWallet.getID()
//           val wallet = LitePal.find<PWallet>(id)
//           binding.tvAddress.text = wallet?.phone
//       }catch (e:Exception){
//           e.printStackTrace()
//       }

        binding.rlTitle.layoutParams = binding.rlTitle.layoutParams.apply {
            height += BarUtils.getStatusBarHeight(requireContext())
        }
        BarUtils.addPaddingTopEqualStatusBarHeight(requireContext(), binding.rlTitle)
        loginDelegate.current.observe(viewLifecycleOwner) {
            if (it.isLogin()) {
                avatar = it.avatar
                nickname = it.nickname
                // 缓存到 MMKV，供账户管理页面同步读取
                AccountProfileCache.save(it.address, it.avatar, it.nickname)
                binding.ivAvatar.load(it.avatar, R.drawable.default_avatar_big)
                if (it.nickname.isEmpty()) {
                    binding.tvName.text = getString(R.string.chat_no_nickname)
                } else {
                    binding.tvName.text = it.nickname
                }

                binding.tvAddress.text = it.address
                binding.tvTitle.text = it.nickname
                binding.tvTitle.alpha = titleAlpha
                content = it.address
                val url = AppConfig.shareCode(content)
                binding.ivQrCode.setImageBitmap(CodeUtils.createQRCode(url, 350))
            }
        }
        loginDelegate.preference.msgNotify.observe(viewLifecycleOwner) {
            loginDelegate.preference.apply {
                val item = menus.find { it.icon == R.drawable.icon_my_setting }
                if (item != null) {
                    item.mark = !(SET_MSG_NOTIFY && SET_CALL_NOTIFY)
                    mAdapter.notifyItemChanged(mAdapter.data.indexOf(item))
                }
            }
        }
        loginDelegate.preference.callNotify.observe(viewLifecycleOwner) {
            loginDelegate.preference.apply {
                val item = menus.find { it.icon == R.drawable.icon_my_setting }
                if (item != null) {
                    item.mark = !(SET_MSG_NOTIFY && SET_CALL_NOTIFY)
                    mAdapter.notifyItemChanged(mAdapter.data.indexOf(item))
                }
            }
        }
        binding.rvMenu.isNestedScrollingEnabled = false
        binding.rvMenu.layoutManager = LinearLayoutManager(context)
        mAdapter = object :
            BaseQuickAdapter<AccountMenuItem, BaseViewHolder>(R.layout.item_account_menu, menus) {
            override fun convert(holder: BaseViewHolder, item: AccountMenuItem) {
                holder.setImageResource(R.id.iv_icon, item.icon)
                holder.setText(R.id.tv_name, item.name)
                holder.setText(R.id.tv_sub_name, item.subname)
                holder.setGone(R.id.dot_check, !item.mark)
            }
        }
        mAdapter.addChildClickViewIds(R.id.rl_container)
        mAdapter.setOnItemChildClickListener { _, v, position ->
            if (v.id == R.id.rl_container) {
                val route = menus[position].route
                if (route != null) {
                    val path = route.first
                    val bundle = route.second
                    ARouter.getInstance().build(path).with(bundle).navigation()
                } else {
                    menus[position].action?.invoke()
                }
            }
        }
        binding.rvMenu.adapter = mAdapter
        // 企业成员/模块开关等请求已移至 onResume（页面真正可见时才请求，见下方）

        if(W.appType == WConfig.APP_NCHAT) {
            binding.rlQr.visibility = View.GONE
        }
    }

    override fun initData() {
        viewModel.moduleState.observe(viewLifecycleOwner) {
            FunctionModules.parseModulesState(it)
        }
//        FunctionModules.enableWalletLive.observe(viewLifecycleOwner) {
//            if (it) {
//                menus.add(0, walletModule)
//                mAdapter.notifyItemInserted(0)
//            } else {
//                val index = menus.indexOf(walletModule)
//                if (index != -1) {
//                    menus.removeAt(index)
//                    mAdapter.notifyItemRemoved(index)
//                }
//            }
//        }
        FunctionModules.enableLiveLive.observe(viewLifecycleOwner) {
            binding.ivToLive.setVisible(it)
        }
        FunctionModules.enableRedPacketLive.observe(viewLifecycleOwner) {
            if (it) {
                menus.add(0, redPacketModule)
                mAdapter.notifyItemInserted(0)
            } else {
                val index = menus.indexOf(redPacketModule)
                if (index != -1) {
                    menus.removeAt(index)
                    mAdapter.notifyItemRemoved(index)
                }
            }
        }
        // 模块开关（GET /module/state）在 onResume 中请求：只有「我的」Tab 展示时才发请求
    }

    override fun setEvent() {
        binding.ivScan.setOnClickListener(this)
        binding.ivSearch.setOnClickListener(this)
        binding.ivAdd.setOnClickListener(this)
        binding.ivAvatar.setOnClickListener(this)
        binding.tvName.setOnClickListener(this)
        binding.tvAddress.setOnClickListener(this)
        binding.ivQrCode.setOnClickListener(this)
        binding.switchAccount.setOnClickListener(this)
        binding.exitLogin.setOnClickListener(this)
        binding.ivToLive.setOnClickListener(this)
        binding.nsvContainer.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val start = 10.dp + binding.ivAvatar.height + binding.tvName.height / 2
            val end = start + binding.tvName.height
            titleAlpha = if (scrollY < end) {
                if (scrollY > start) {
                    (scrollY - start) * 1.0f / (end - start)
                } else {
                    0.0f
                }
            } else {
                1.0f
            }
            binding.tvTitle.alpha = titleAlpha
        })
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.iv_scan -> {
                v.checkSingle(1000) { ARouter.getInstance().build(MainModule.QR_SCAN).navigation() }
            }

            R.id.iv_search -> {
                ARouter.getInstance().build(MainModule.SEARCH_LOCAL).navigation()
                /* ARouter.getInstance().build(BizModule.WEB_ACTIVITY)
                     .withString("url", "https://www.baidu.com/")
                     .withBoolean("showTitle", true)
                     .withString("title", "区块链浏览器")
                     .navigation()*/
            }

            R.id.iv_add -> {
                HomeActionPopup.create(requireContext(), this).show(binding.ivAdd)
            }

            R.id.iv_avatar -> {
                val intent = Intent(requireContext(), EditAvatarActivity::class.java)
                    .putExtra("avatar", loginDelegate.current.value?.avatar)
                startActivity(
                    intent, ActivityOptionsCompat.makeSceneTransitionAnimation(
                        requireActivity(), v, "shareImage"
                    ).toBundle()
                )
            }

            R.id.tv_name -> ARouter.getInstance().build(MainModule.EDIT_INFO).navigation()
            R.id.tv_address -> {
                val clipData = ClipData.newPlainText("message", content)
                requireContext().clipboardManager?.setPrimaryClip(clipData)
                toast(R.string.chat_tips_copy_address)
            }

            R.id.iv_qr_code -> {
                val intent = Intent(requireActivity(), QRCodeActivity::class.java)
                startActivity(
                    intent, ActivityOptionsCompat.makeSceneTransitionAnimation(
                        requireActivity(),
                        Pair(v, "shareQR"),
                        Pair(binding.ivAvatar, "shareImage"),
                        Pair(binding.tvName, "shareName"),
                        Pair(binding.tvAddress, "shareAddress")
                    ).toBundle()
                )
            }

            R.id.switch_account -> {
                ARouter.getInstance().build(MainModule.ACCOUNT_MANAGER).navigation()
            }

            R.id.exit_login -> {
                val dialog = EasyDialog.Builder()
                    .setHeaderTitleColor(Color.RED)
                    .setHeaderTitle(getString(R.string.logout_tip))
                    .setBottomLeftText(getString(R.string.biz_cancel))
                    .setBottomRightText(getString(R.string.biz_confirm))
                    .setContent(getString(R.string.biz_logout_message))
                    .setContentColor(Color.RED)
                    .setBottomLeftClickListener(null)
                    .setBottomRightClickListener { dialog ->
                        dialog.dismiss()
                        loginDelegate.performLogout()
                        LitePal.deleteAll<PWallet>("mnem is null or mnem == ?","")
                        activity?.finish()
                        ARouter.getInstance().build(MainModule.CHOOSE_LOGIN).navigation()

                    }.create(activity)
                dialog.show()
            }
            // 弹窗中的按钮
            R.id.scan -> {
                v.checkSingle(1000) { ARouter.getInstance().build(MainModule.QR_SCAN).navigation() }
            }

            R.id.create_group -> {

                ARouter.getInstance().build(MainModule.CREATE_INVITE_GROUP).navigation()


            }

            R.id.join -> {
                ARouter.getInstance().build(MainModule.SEARCH_ONLINE).navigation()
            }

            R.id.qr_code -> {
                ARouter.getInstance().build(MainModule.QR_CODE).navigation()
            }

            R.id.create_company -> {
                oaService?.openCreateCompanyPage()
            }

            R.id.okr -> {
                oaService?.openOKRPage(null)
            }

            R.id.iv_to_live -> {
                lifecycleScope.launch {
                    val address = binding.tvAddress.text.toString()
                    loading(true)
                    try {
                        liveService?.login(requireContext(), LiveService.FROM_ACCOUNT, "")
                    } catch (e: Exception) {
                        toast(e.message ?: "")
                    } finally {
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 以下接口请求只在「我的」Tab 真正展示在前台时发起，其它 Tab 可见时不会请求
        loginDelegate.updateInfo()
        viewModel.fetchModuleState()
        // 企业成员列表（OA）：仅页面可见时刷新（内部会等待模块配置初始化，无企业时会自动跳过）
        lifecycleScope.launch {
            oaService?.loadCompanyUsers()
        }
        // 每次回到“我的”页补同步收款地址（幂等；30 秒内不重复；成功静默，失败展示差异诊断）
        val now = System.currentTimeMillis()
        if (now - lastChainSyncAttempt >= chainSyncMinInterval) {
            lastChainSyncAttempt = now
            lifecycleScope.launch {
                val ret = runCatching { walletService?.updateChainAddress(null) }.getOrNull()
                if (ret != "ok" && ret != "local_empty") {
                    showChainSyncDiagnose()
                }
            }
        }
    }

    /** 同步失败时查询并展示 本机 vs 链上 差异，便于定位 */
    private fun showChainSyncDiagnose() {
        lifecycleScope.launch {
            val diff = runCatching { walletService?.queryChainAddressSyncState() }.getOrNull()
            if (diff.isNullOrEmpty()) {
                toast(R.string.chat_chain_sync_fail)
            } else {
                toast(diff)
            }
        }
    }

    private val menus by lazy {
        mutableListOf(
            AccountMenuItem(
                R.drawable.icon_my_language,
                getString(R.string.chat_account_menu_language),
                "",
                false,
                MainModule.LANGUAGE_SELECT to null
            ),
            AccountMenuItem(
                R.drawable.icon_my_server,
                getString(R.string.chat_account_menu_server),
                "",
                false,
                MainModule.SERVER_MANAGE to null
            ),
            AccountMenuItem(
                R.drawable.icon_my_down,
                getString(R.string.chat_account_menu_share),
                "",
                false,
                MainModule.DOWNLOAD to null
            ),
            AccountMenuItem(
                R.drawable.icon_my_safe,
                getString(R.string.chat_account_menu_security),
                "",
                false,
                MainModule.SECURITY_SET to null
            ),
            AccountMenuItem(
                R.drawable.icon_my_setting,
                getString(R.string.chat_account_menu_setting),
                "",
                false,
                MainModule.SETTING_CENTER to null
            ),
            /*AccountMenuItem(
                R.mipmap.icon_my_feed,
                getString(R.string.chat_account_menu_feedback),
                "",
                false,
                MainModule.FEEDBACK to null
            ),*/
            AccountMenuItem(
                R.drawable.icon_my_update,
                getString(R.string.chat_account_menu_update),
                "V${requireContext().versionName}",
                false,
                null
            ) {
                toast(R.string.chat_update_checking_update)
                mainService?.checkUpdate(requireActivity()) { _, msg ->
                    if (msg.isNotEmpty()) toast(msg)
                }
            },
        )
    }
}