package com.fzm.chat.wallet.ui.coins

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import com.fzm.wallet.sdk.db.entity.Coin

/**
 * @author zhengjy
 * @since 2022/03/01
 * Description:
 */
class ChainCoinFragment : AbstractCoinFragment() {

    companion object {
        fun create(coins: ArrayList<Coin>): ChainCoinFragment {
            return ChainCoinFragment().apply {
                arguments = bundleOf("coins" to coins)
            }
        }
    }

    private val coins = mutableListOf<Coin>()

    override fun initView(view: View, savedInstanceState: Bundle?) {
        coins.clear()
        coins.addAll(arguments?.getSerializable("coins") as ArrayList<Coin>)
        super.initView(view, savedInstanceState)
    }

    override fun initData() {
        super.initData()
        viewModel.homeCoins.observe(viewLifecycleOwner) { list ->
            var changed = false
            coins.forEachIndexed { index, coin ->
                val temp = viewModel.netCoinsMap[coin.netId]
                if (temp != null && coin.status != temp.status) {
                    Log.d(
                        "CoinManage",
                        "chain sync ${coin.name}: status ${coin.status} -> ${temp.status}"
                    )
                    coin.status = temp.status
                    coin.id = temp.id
                    changed = true
                }
            }
            // 有币种状态变化(如添加成功)时整表重绘，确保 + 变 -（或 - 变 +）
            if (changed) adapter.notifyDataSetChanged()
        }
        // 主动刷新本地币种，同步各主链币的已添加状态(netCoinsMap)
        viewModel.refreshHomeCoins()
    }

    override fun loadCoins() {
        Log.d(
            "CoinManage",
            "chain coins count=${coins.size} -> " +
                coins.joinToString { "${it.name}:${it.status}" }
        )
        onCoinsUpdate(coins)
    }
}