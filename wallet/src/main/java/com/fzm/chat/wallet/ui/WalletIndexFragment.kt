package com.fzm.chat.wallet.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.base.EmptyFeatureFragment
import com.fzm.chat.biz.widget.FadePageTransformer
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.redpacket.ModuleAsset
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.databinding.FragmentWalletIndexBinding
import com.zjy.architecture.ext.*
import com.zjy.architecture.util.other.BarUtils
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2021/10/13
 * Description:
 */
@Route(path = WalletModule.WALLET)
class WalletIndexFragment : BizFragment() {

    private val viewModel by viewModel<WalletViewModel>()

    companion object {
        fun create(showBack: Boolean, showNFT: Boolean): WalletIndexFragment {
            return WalletIndexFragment().apply {
                arguments = bundleOf(
                    "showBack" to showBack,
                    "showNFT" to showNFT
                )
            }
        }
    }

    @JvmField
    @Autowired
    var showBack: Boolean = false

    @JvmField
    @Autowired
    var showNFT: Boolean = true

    private var accountAssets = mutableListOf<ModuleAsset>()

    private val binding by init<FragmentWalletIndexBinding>()

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {
        BarUtils.addPaddingTopEqualStatusBarHeight(requireContext(), binding.llHeader)
        ARouter.getInstance().inject(this)

        if (showNFT) {
            binding.vpAssets.apply {
                adapter = object : FragmentPagerAdapter(
                    childFragmentManager,
                    BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
                ) {
                    override fun getCount() = 2

                    override fun getItem(position: Int): Fragment {
                        return if (position == 0)
                            TokenListFragment.create(itemLayout = R.layout.item_coin)
                        else
//                            NFTListFragment.create({
//                                ARouter.getInstance().build(WalletModule.ASSET_DETAIL)
//                                    .withSerializable("coin", it)
//                                    .navigation()
//                            }, itemLayout = R.layout.layout_wallet_index_assets)
                            EmptyFeatureFragment.create()
                    }

                    override fun getPageTitle(position: Int): CharSequence {
                        return if (position == 0) getString(R.string.wallet_tab_assets) else getString(R.string.wallet_tab_nft)
                    }
                }
                addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                    override fun onPageScrolled(
                        position: Int,
                        positionOffset: Float,
                        positionOffsetPixels: Int
                    ) {

                    }

                    override fun onPageSelected(position: Int) {
                        // 添加入口已移到标题栏右侧(+)，无需处理悬浮按钮
                    }

                    override fun onPageScrollStateChanged(state: Int) {

                    }

                })
                setPageTransformer(false, FadePageTransformer())
                offscreenPageLimit = 1
                binding.tabLayout.setViewPager(this)
                binding.tabLayout.onPageSelected(0)
            }
        } else {
            binding.vpAssets.apply {
                adapter = object : FragmentPagerAdapter(
                    childFragmentManager,
                    BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
                ) {
                    override fun getCount() = 1

                    override fun getItem(position: Int): Fragment {
                        return TokenListFragment.create(itemLayout = R.layout.item_coin)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.getAccountAssetByAddress()
        // 从添加资产页等返回时，立即刷新资产币种列表（不等轮询）
        viewModel.refreshAssetsSnapshot()
        // 主界面资产 Tab：头部为品牌蓝延伸进状态栏，需浅色(白)状态栏图标
        if (!showBack) {
            BarUtils.setStatusBarLightMode(requireActivity(), false)
        }
    }

    override fun onPause() {
        // 离开资产 Tab 后恢复主界面深色状态栏图标（其它页面为浅色背景）
        if (!showBack) {
            BarUtils.setStatusBarLightMode(requireActivity(), true)
        }
        super.onPause()
    }

    override fun initData() {
        viewModel.loading.observe(viewLifecycleOwner) {
            setupLoading(it)
        }
        viewModel.accountAsset.observe(viewLifecycleOwner) {
            if (it.none { account -> account.balance != "0" }) {
                binding.extractLayout.gone()
            } else {
                accountAssets.clear()
                accountAssets.addAll(it)
                binding.extractLayout.visible()
                binding.tvExtractTips.mediumBold()
            }
        }
//        lifecycleScope.launch {
//            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
//                BWallet.get().current.collect {
//                    if (viewModel.isLogin()) {
//                        if (it is EmptyWallet) {
//                            if (binding.fabAddCoins.isVisible) {
//                                binding.fabAddCoins.gone()
//                            }
//                            EasyDialog.Builder()
//                                .setHeaderTitle(resources.getString(R.string.biz_tips))
//                                .setContent("需要重新登录以创建钱包")
//                                .setBottomLeftText(resources.getString(R.string.biz_cancel))
//                                .setBottomRightText("重新登录")
//                                .setBottomRightClickListener { dialog ->
//                                    dialog.dismiss()
//                                    viewModel.performLogout()
//                                    ActivityUtils.popUpTo("")
//                                    ARouter.getInstance().build(MainModule.CHOOSE_LOGIN).navigation()
//                                }
//                                .create(requireContext())
//                                .show()
//                        } else {
//                            if (binding.fabAddCoins.isGone) {
//                                binding.fabAddCoins.visible()
//                            }
//                        }
//                    }
//                }
//            }
//        }
    }

    override fun setEvent() {
        binding.ctbTitle.setLeftVisible(showBack)
        binding.ctbTitle.setOnLeftClickListener { activity?.finish() }
        binding.ivScan.setOnClickListener {
            ARouter.getInstance().build(MainModule.QR_SCAN).navigation()
        }
        binding.ivAdd.setOnClickListener {
            when (W.appType) {
                WConfig.APP_CIP -> {
                    ARouter.getInstance().build(WalletModule.ADD_CUS_COINS)
                        .navigation(requireContext())
                }

                else -> {
                    ARouter.getInstance().build(WalletModule.ADD_COINS)
                        .withTransition(R.anim.biz_slide_bottom_in, 0)
                        .navigation(requireContext())
                }
            }
        }

        binding.extractBtn.singleClick {
            lifecycleScope.launch {
                viewModel.withDrawAssets(accountAssets)
                binding.extractLayout.gone()
                toast(getString(R.string.wallet_withdraw_submitted))
            }
        }
    }
}