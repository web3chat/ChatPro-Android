package com.fzm.chat.wallet.ui.coins

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.ui.PasswordVerifyFragment
import com.fzm.chat.router.biz.PasswordVerifier
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.route
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.databinding.LayoutCoinManagementBinding
import com.fzm.wallet.sdk.db.entity.Coin
import com.zjy.architecture.ext.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.androidx.viewmodel.ext.android.getViewModel
import kotlin.coroutines.resume

/**
 * @author zhengjy
 * @since 2022/03/01
 * Description:
 */
abstract class AbstractCoinFragment : BizFragment() {

    protected lateinit var adapter: CoinManageAdapter

    protected val viewModel by lazy { requireActivity().getViewModel<CoinViewModel>() }

    protected val binding by init<LayoutCoinManagementBinding>()

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {
        binding.rvCoins.layoutManager = LinearLayoutManager(requireContext())
        adapter = object : CoinManageAdapter(null) {
            override fun updateCoin(coin: Coin, index: Int, add: Boolean) {
                onUpdateCoin(coin, index, add)
            }
        }
        binding.rvCoins.adapter = adapter
        setupCoinDrag()
    }

    /**
     * 币种管理页 +/- 的统一入口，子类可按需覆写。
     * 默认行为：添加前先在主线程弹出密码框，输入后调用 SDK；移除走删除。
     */
    open fun onUpdateCoin(coin: Coin, index: Int, add: Boolean) {
        Log.d("CoinManage", "onUpdateCoin add=$add index=$index name=${coin.name} status=${coin.status}")
        lifecycleScope.launch {
            if (add) {
                val password = requestPassword()
                Log.d("CoinManage", "requestPassword returned len=${password.length}")
                val ok = viewModel.addCoin(coin, index, password)
                if (ok) {
                    coin.status = Coin.STATUS_ENABLE
                    adapter.notifyDataSetChanged()
                    Log.d("CoinManage", "onUpdateCoin add ok, refreshed list")
                }
            } else {
                val ok = viewModel.removeCoin(coin, index)
                if (ok) {
                    coin.status = 0
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun initData() {
        viewModel.operationError.observe(viewLifecycleOwner) { msg ->
            msg?.let { toast(it) }
        }
        // 币种状态变化(添加/隐藏/显示成功)后，立即同步当前列表对应行的 + / -
        viewModel.coinChanged.observe(viewLifecycleOwner) { changed ->
            changed?.let { applyChangedCoin(it) }
        }
        loadCoins()
    }

    private fun applyChangedCoin(changed: Coin) {
        if (!::adapter.isInitialized) return
        var updated = false
        val targetStatus = changed.status
        adapter.data.forEach { c ->
            val same = (c.netId != null && changed.netId != null && c.netId == changed.netId) ||
                (c.name == changed.name && c.chain == changed.chain)
            if (same && c.status != targetStatus) {
                c.status = targetStatus
                if (targetStatus == Coin.STATUS_ENABLE) {
                    c.id = changed.id
                }
                updated = true
            }
        }
        if (updated) {
            Log.d("CoinManage", "applyChangedCoin: ${changed.name} -> status=$targetStatus, notifyDataSetChanged")
            adapter.notifyDataSetChanged()
        } else {
            Log.d("CoinManage", "applyChangedCoin: ${changed.name} 无匹配行(status=$targetStatus)")
        }
    }

    protected fun onCoinsUpdate(coins: List<Coin>) {
        adapter.setDiffNewData(coins.toMutableList())
    }

    override fun setEvent() {
        
    }

    abstract fun loadCoins()

    open fun setupCoinDrag() {

    }

    private suspend fun requestPassword() = suspendCancellableCoroutine<String> { cont ->
        if (viewModel.hasPassword()) {
            val dialog by route<PasswordVerifyFragment>(
                MainModule.VERIFY_ENC_PWD,
                bundleOf("type" to 0)
            )
            val resolved = dialog
            if (resolved == null) {
                toast(R.string.biz_tips)
                cont.cancel()
                return@suspendCancellableCoroutine
            }
            resolved.setOnPasswordVerifyListener(object :
                PasswordVerifier.OnPasswordVerifyListener {
                override fun onSuccess(password: String) {
                    Log.d("CoinManage", "pwd verify success")
                    cont.resume(password)
                }

                override fun onCancel() {
                    Log.d("CoinManage", "pwd verify cancelled")
                    cont.cancel()
                }
            })
            resolved.show(childFragmentManager, "VERIFY_ENC_PWD")
        } else {
            val dialog by route<PasswordVerifyFragment>(
                MainModule.SET_ENC_PWD,
                bundleOf("showWords" to 0)
            )
            val resolved = dialog
            if (resolved == null) {
                toast(R.string.biz_tips)
                cont.cancel()
                return@suspendCancellableCoroutine
            }
            resolved.setOnPasswordVerifyListener(object :
                PasswordVerifier.OnPasswordVerifyListener {
                override fun onSuccess(password: String) {
                    Log.d("CoinManage", "pwd set success")
                    cont.resume(password)
                }

                override fun onCancel() {
                    Log.d("CoinManage", "pwd set cancelled")
                    cont.cancel()
                }
            })
            resolved.show(childFragmentManager, "SET_ENC_PWD")
        }
    }
}