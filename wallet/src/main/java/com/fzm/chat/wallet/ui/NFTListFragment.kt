package com.fzm.chat.wallet.ui

import androidx.core.os.bundleOf
import com.fzm.chat.wallet.R

/**
 * @author zhengjy
 * @since 2021/10/19
 * Description:
 */
class NFTListFragment : AssetsListFragment() {

    companion object {
        fun create(
            layoutId: Int = R.layout.fragment_wallets,
            itemLayout: Int = R.layout.item_wallet_assets
        ): NFTListFragment {
            return NFTListFragment().apply {
                arguments = bundleOf(
                    "layoutId" to layoutId,
                    "itemLayout" to itemLayout
                )
            }
        }
    }

    override fun observeAssets() = viewModel.nftAssets

    override fun getAssetsList(showLoading: Boolean) = viewModel.getNFTAssetList(showLoading)
}