package com.fzm.chat.wallet.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import com.fzm.chat.core.data.bean.Option
import com.fzm.chat.core.data.bean.message
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.WalletSDK
import com.fzm.wallet.sdk.db.entity.Coin
import dtalk.biz.Biz

/**
 * @author zhengjy
 * @since 2021/10/19
 * Description:
 */
class TokenListFragment : AssetsListFragment() {

    companion object {
        fun create(
            layoutId: Int = R.layout.fragment_wallets,
            itemLayout: Int = R.layout.item_wallet_assets,
            coinFilter: ((Coin) -> Boolean)? = null
        ): TokenListFragment {
            return TokenListFragment().apply {
                arguments = bundleOf(
                    "layoutId" to layoutId,
                    "itemLayout" to itemLayout
                )
                coinFilter?.also { setCoinFilter(it) }
            }
        }
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        MessageSubscription.subscribeMessage(this) { opt ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@subscribeMessage
            if (opt.option != Option.ADD_MSG && opt.option != Option.UPDATE_CONTENT) return@subscribeMessage
            when (opt.message.msgType) {
                Biz.MsgType.Transfer_VALUE,
                Biz.MsgType.RedPacket_VALUE -> WalletSDK.requestCoinBalanceRefresh()
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WalletSDK.requestCoinBalanceRefresh()
    }

    override fun onDestroyView() {
        MessageSubscription.unsubscribeMessage(this)
        super.onDestroyView()
    }

    override fun observeAssets() = viewModel.moduleAssets

    override fun getAssetsList(showLoading: Boolean) {

    }
}