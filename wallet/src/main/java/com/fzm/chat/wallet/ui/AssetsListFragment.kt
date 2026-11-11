package com.fzm.chat.wallet.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.router.biz.ResultReceiver
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.WalletSDK
import com.fzm.chat.wallet.data.format4
import com.fzm.chat.wallet.ui.coins.CoinDiffCallback
import com.fzm.wallet.sdk.db.entity.Coin
import com.jeremyliao.liveeventbus.LiveEventBus
import com.kongzue.dialogx.dialogs.MessageDialog
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.getViewModel
import org.litepal.LitePal
import org.litepal.extension.findFirst

/**
 * @author zhengjy
 * @since 2021/10/19
 * Description:
 */
abstract class AssetsListFragment : BizFragment(), ResultReceiver<Coin> {

    private var _layoutId: Int = 0
    private var itemLayout: Int = 0

    /** 与宿主 Activity 共用 [WalletViewModel]，侧栏/ViewPager 内子 Fragment 可立即复用已拉取的 [moduleAssets]。 */
    protected val viewModel by lazy(LazyThreadSafetyMode.NONE) {
        requireActivity().getViewModel<WalletViewModel>()
    }

    protected lateinit var mAdapter: BaseQuickAdapter<Coin, BaseViewHolder>

    private val _root by lazy { LayoutInflater.from(requireContext()).inflate(_layoutId, null) }

    override val root: View
        get() = _root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _layoutId = arguments?.getInt("layoutId", R.layout.fragment_wallets)
            ?: R.layout.fragment_wallets
        itemLayout = arguments?.getInt("itemLayout", R.layout.item_wallet_assets)
            ?: R.layout.item_wallet_assets
    }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        val rvAssets = root.findViewById<RecyclerView>(R.id.rv_assets)
        val refresh = root.findViewById<SwipeRefreshLayout>(R.id.refresh)
        rvAssets.layoutManager = LinearLayoutManager(requireContext())
        mAdapter = object : BaseQuickAdapter<Coin, BaseViewHolder>(itemLayout, null) {
            override fun convert(holder: BaseViewHolder, item: Coin) {
                Log.v(
                    "wallet",
                    "name:${item.name},balance: ${item.balance},contract_address: ${item.contract_address}"
                )
                holder.setText(R.id.tv_coin, item.name)
                if (item.name.isNullOrEmpty()) {
                    holder.setGone(R.id.tv_name, true)
                } else {
                    holder.setGone(R.id.tv_name, false)
                    holder.setText(R.id.tv_name, item.nickname)
                }
                val formatValue = format4(item.balance.toDouble())
                holder.setText(R.id.tv_balance, formatValue)
                /*if (holder.getView<TextView>(R.id.tv_price).isVisible) {
                    holder.setText(R.id.tv_price, "≈¥${item.rmb}")
                }*/
                val icicon = holder.getView<ImageView>(R.id.ic_coin)
                val tvicon = holder.getView<TextView>(R.id.tv_icon_coin)

                // 币种没有图标(URL 为空)时，用币种名首字母作为圆形默认图标，避免显示空白
                val coinName = item.name ?: ""
                if (item.netId.isNullOrEmpty() || item.icon.isNullOrEmpty()) {
                    icicon.visibility = View.GONE
                    tvicon.visibility = View.VISIBLE
                    tvicon.text = coinName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                } else {
                    holder.getView<ImageView>(R.id.ic_coin).load(item.icon)
                    icicon.visibility = View.VISIBLE
                    tvicon.visibility = View.GONE
                }
            }
        }
        mAdapter.setOnItemClickListener { _, _, position ->
            val item = mAdapter.data[position]
            if (listener != null) {
                listener?.onResult(item)
            } else {
                ARouter.getInstance().build(WalletModule.ASSET_DETAIL)
                    .withSerializable("coin", item)
                    .navigation()
            }
        }
        mAdapter.setOnItemLongClickListener { adapter, view, position ->
            val messageDialog = MessageDialog.build()
            messageDialog.title = getString(R.string.biz_tips)
            messageDialog.message = getString(R.string.wallet_confirm_delete)
            messageDialog.okButton = getString(R.string.biz_confirm)
            messageDialog.cancelButton = getString(R.string.biz_cancel)
            messageDialog.setOkButton { dialog, v ->
                messageDialog.dismiss()
                val item = mAdapter.data[position]
                if (item.name == "BNB" || item.name == "POL") {
                    toast(getString(R.string.wallet_main_chain_cannot_delete))
                } else {
                    item.delete()
                    mAdapter.notifyItemRemoved(position)
                }

                true
            }
            messageDialog.show()

            true
        }
        rvAssets.adapter = mAdapter
        mAdapter.setEmptyView(R.layout.layout_empty_assets)
        mAdapter.isUseEmpty = false
        mAdapter.setDiffCallback(CoinDiffCallback())
        refresh.setColorSchemeColors(
            ContextCompat.getColor(
                requireContext(),
                R.color.biz_wallet_accent
            )
        )
        refresh.setOnRefreshListener {
            getAssetsList(false)
            refresh.isRefreshing = false
        }

        coinFilter?.also { viewModel.setCoinFilter(it) }
        observeAssets().observe(viewLifecycleOwner) { list ->
            mAdapter.isUseEmpty = true
            // 仅当本 Fragment 设置了 coinFilter 时缩小列表（如侧栏选币）；钱包「资产」页未设置，仍为全量
            val shown = coinFilter?.let { f -> list.filter(f) } ?: list
            mAdapter.setList(shown)
        }
    }


    abstract fun observeAssets(): LiveData<List<Coin>>

    abstract fun getAssetsList(showLoading: Boolean)

    override fun onResume() {
        super.onResume()
        // 资产页展示在前台后才开启余额/行情定时轮询（其它页面不请求 wallet-coin）
        WalletSDK.setBalancePollingEnabled(true)
        getAssetsList(false)
    }

    override fun onPause() {
        // 页面不可见（切到其它 Tab/Activity）则停止定时轮询
        WalletSDK.setBalancePollingEnabled(false)
        super.onPause()
    }

    protected var listener: ResultReceiver.OnResultListener<Coin>? = null

    override fun setOnResultReceiver(listener: ResultReceiver.OnResultListener<Coin>?) {
        this.listener = listener
    }

    private var coinFilter: ((Coin) -> Boolean)? = null

    fun setCoinFilter(coinFilter: (Coin) -> Boolean) {
        this.coinFilter = coinFilter
    }

    override fun initData() {

    }

    override fun setEvent() {

    }
}