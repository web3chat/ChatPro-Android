package com.fzm.chat.wallet.ui

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.widget.FadePageTransformer
import com.fzm.chat.router.biz.ResultReceiver
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.databinding.FragmentDrawerAssetsBinding
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.utils.fullChain
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.visible
import org.koin.androidx.viewmodel.ext.android.getViewModel

/**
 * @author zhengjy
 * @since 2021/10/19
 * Description:
 */
@Route(path = WalletModule.DRAWER_ASSETS)
class DrawerAssetsFragment : BizFragment(), ResultReceiver<Coin> {

    private var resultListener: ResultReceiver.OnResultListener<Coin>? = null

    override fun setOnResultReceiver(listener: ResultReceiver.OnResultListener<Coin>?) {
        this.resultListener = listener
    }

    @JvmField
    @Autowired
    var showNFT: Boolean = true

    @JvmField
    @Autowired
    var chain: String? = null

    @JvmField
    @Autowired
    var platform: String? = null

    /** 发红包侧栏选币：仅按 [Coin.name] 隐藏 ETH、BNB，不做链过滤 */
    @JvmField
    @Autowired
    var hideEthAndBnb: Boolean = false

    private val binding by init<FragmentDrawerAssetsBinding>()

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {
        ARouter.getInstance().inject(this)
        fun coinMatches(coin: Coin): Boolean {
            if (hideEthAndBnb) {
                val n = coin.name?.trim()?.uppercase().orEmpty()
                return n != "ETH" && n != "BNB" && coin.platform !="bty"
            }
            return (chain == null || coin.fullChain == chain || coin.chain == chain) &&
                (platform == null || coin.platform == platform)
        }
        if (showNFT) {
            binding.llTab.visible()
            binding.vpAssets.apply {
                adapter = object : FragmentStateAdapter(this@DrawerAssetsFragment) {
                    override fun getItemCount() = 2

                    override fun createFragment(position: Int): Fragment {
                        return if (position == 0) TokenListFragment.create() {
                            coinMatches(it)
                        }.also { it.setOnResultReceiver(resultListener) } else NFTListFragment.create()
                    }
                }
                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        switchChoose(position)
                    }
                })
                setPageTransformer(FadePageTransformer())
                offscreenPageLimit = 1
                post { setCurrentItem(0, false) }
            }
        } else {
            binding.llTab.gone()
            binding.vpAssets.apply {
                adapter = object : FragmentStateAdapter(this@DrawerAssetsFragment) {
                    override fun getItemCount() = 1

                    override fun createFragment(position: Int): Fragment {
                        return TokenListFragment.create() {
                            coinMatches(it)
                        }.also { it.setOnResultReceiver(resultListener) }
                    }
                }
                offscreenPageLimit = 1
                post { setCurrentItem(0, false) }
            }
        }
        // 与侧栏内 TokenList 共用 Activity 级 WalletViewModel：进入发红包页即可拉取 moduleAssets，打开抽屉时列表不必再等冷启动。
        requireActivity().getViewModel<WalletViewModel>().moduleAssets.observe(viewLifecycleOwner) {}
    }

    private fun switchChoose(index: Int) {
        when (index) {
            0 -> {
                binding.token.setTextColor(ContextCompat.getColor(requireContext(), R.color.biz_color_accent))
                binding.token.setBackgroundResource(R.drawable.chat_round_bg_r17)
                binding.nft.setTextColor(ContextCompat.getColor(requireContext(), R.color.biz_text_grey_light))
                binding.nft.setBackgroundResource(0)
            }
            1 -> {
                binding.token.setTextColor(ContextCompat.getColor(requireContext(), R.color.biz_text_grey_light))
                binding.token.setBackgroundResource(0)
                binding.nft.setTextColor(ContextCompat.getColor(requireContext(), R.color.biz_color_accent))
                binding.nft.setBackgroundResource(R.drawable.chat_round_bg_r17)
            }
        }
    }

    override fun initData() {

    }

    override fun setEvent() {
        binding.token.setOnClickListener {
            if (binding.vpAssets.currentItem != 0) {
                binding.vpAssets.currentItem = 0
            }
        }
        binding.nft.setOnClickListener {
            if (binding.vpAssets.currentItem != 1) {
                binding.vpAssets.currentItem = 1
            }
        }
    }

}