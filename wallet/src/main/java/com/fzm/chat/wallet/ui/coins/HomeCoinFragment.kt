package com.fzm.chat.wallet.ui.coins

import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter.base.listener.OnItemDragListener
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.db.entity.Coin
import kotlinx.coroutines.launch

/**
 * @author zhengjy
 * @since 2022/03/01
 * Description:钱包页币种列表编辑
 */
class HomeCoinFragment : AbstractCoinFragment() {

    companion object {
        fun create(): HomeCoinFragment {
            return HomeCoinFragment()
        }
    }

    private var startPos = 0

    override fun setupCoinDrag() {
        adapter.draggableModule.isDragEnabled = true
        adapter.draggableModule.isDragOnLongPressEnabled = true
        adapter.draggableModule.itemTouchHelperCallback.setDragMoveFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN)
        adapter.draggableModule.setOnItemDragListener(object : OnItemDragListener {
            override fun onItemDragStart(viewHolder: RecyclerView.ViewHolder?, pos: Int) {
                startPos = pos
            }

            override fun onItemDragMoving(
                source: RecyclerView.ViewHolder?,
                from: Int,
                target: RecyclerView.ViewHolder?,
                to: Int
            ) {
            }

            override fun onItemDragEnd(viewHolder: RecyclerView.ViewHolder?, pos: Int) {
                if (startPos < pos) {
                    for (i in startPos..pos) {
                        adapter.data[i].apply {
                            sort = i
                            BWallet.get().changeCoinOrder(this, i)
                        }
                    }
                } else if (startPos > pos) {
                    for (i in pos..startPos) {
                        adapter.data[i].apply {
                            sort = i
                            BWallet.get().changeCoinOrder(this, i)
                        }
                    }
                }
            }
        })
    }

    /**
     * 首页币种管理：+"=""显示/启用（资产页可见），-"=""隐藏/停用（保留记录可再显示）。
     */
    override fun onUpdateCoin(coin: Coin, index: Int, add: Boolean) {
        Log.d("CoinManage", "toggleHomeCoin add=$add name=${coin.name} status=${coin.status}")
        lifecycleScope.launch {
            val ok = viewModel.toggleHomeCoin(coin, add)
            if (ok) {
                coin.status = if (add) Coin.STATUS_ENABLE else 0
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun loadCoins() {
        viewModel.homeCoins.observe(viewLifecycleOwner) { coins ->
            onCoinsUpdate(coins.sorted())
        }
        viewModel.refreshHomeCoins()
    }

    override fun onResume() {
        super.onResume()
        // 从其它页面(如添加自定义币)返回时刷新首页币种
        viewModel.refreshHomeCoins()
    }
}