package com.fzm.chat.wallet.ui.transactions

import android.content.ClipData
import android.graphics.Paint
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.Option
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.toMessagePO
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.utils.isSameOnChainAddressForTx
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.data.bean.TransactionInfo
import com.fzm.chat.wallet.databinding.ActivityTransactionDetailBinding
import com.fzm.chat.wallet.ui.WalletViewModel
import com.fzm.wallet.sdk.utils.GoWallet
import com.zjy.architecture.ext.clipboardManager
import com.zjy.architecture.ext.format
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.visible
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2021/08/09
 * Description:
 */
@Route(path = WalletModule.TRANSACTION_DETAIL)
class TransactionDetailActivity : BizActivity(), View.OnClickListener {

    @JvmField
    @Autowired
    var txId: String = ""

    @JvmField
    @Autowired
    var treaty: String = ""

    @JvmField
    @Autowired
    var chain: String = ""

    @JvmField
    @Autowired
    var platform: String = ""

    @JvmField
    @Autowired
    var symbol: String = ""

    @JvmField
    @Autowired
    var contractAddress: String = ""

    @JvmField
    @Autowired
    var message: ChatMessage? = null

    private var txInfo: TransactionInfo? = null


    private val delegate by inject<LoginDelegate>()
    private val viewModel by viewModel<WalletViewModel>()
    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    private val binding by init { ActivityTransactionDetailBinding.inflate(layoutInflater) }

    override val darkStatusColor: Boolean = true

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
    }

    override fun initData() {
        viewModel.txInfo.observe(this) {
            if (it == null) {
                toast(getString(R.string.wallet_transaction_not_found))
                return@observe
            }
            txInfo = it
            binding.llContainer.visible()
            val myAddr = delegate.getAddress() ?: ""
            val type = when {
                isSameOnChainAddressForTx(it.from, myAddr) -> "-"
                isSameOnChainAddressForTx(it.to, myAddr) -> "+"
                else -> ""
            }
            when (it.status) {
                TransactionInfo.FAIL -> {
                    binding.tvStatus.text = getString(R.string.wallet_transaction_failed)
                    binding.ivStatus.setImageResource(R.drawable.ic_transaction_fail)
                }

                TransactionInfo.PENDING -> {
                    binding.tvStatus.text = getString(R.string.wallet_status_confirming)
                    binding.ivStatus.setImageResource(R.drawable.ic_transaction_waiting)
                }

                TransactionInfo.SUCCESS -> {
                    binding.tvStatus.text = getString(R.string.wallet_transaction_completed)
                    binding.ivStatus.setImageResource(R.drawable.ic_transaction_success)
                }
            }
            binding.tvAmount.text = "$type${it.value}"
            binding.tvCoin.text = symbol
            binding.fromAddress.text = it.from
            binding.toAddress.text = it.to
            binding.txHash.text = it.txId
            binding.txTime.text = (it.blockTime * 1000L).format("yyyy/MM/dd HH:mm:ss")
            binding.remoteNote.text = it.note?.ifEmpty { getString(R.string.wallet_transaction_none) } ?: getString(R.string.wallet_transaction_none)
            message?.apply {
                if (msg.txStatus != it.status) {
                    msg.txAmount = it.value
                    msg.txInvalid = false
                    msg.txStatus = it.status
                    lifecycleScope.launch {
                        database.messageDao().insert(toMessagePO())
                        MessageSubscription.onMessage(Option.UPDATE_CONTENT, this@apply)
                    }
                }
            }
        }
        //val treaty = if(contractAddress.isEmpty()) "2" else "1"

        val coinToken = GoWallet.newCoinType(chain, symbol, platform, treaty,contractAddress)
        if(platform == "btyerc") {
            viewModel.getTransactionById(coinToken.cointype, contractAddress, "", txId)
        }else {
            viewModel.getTransactionById(coinToken.cointype, coinToken.tokenSymbol, contractAddress, txId)
        }

    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.fromAddress.setOnClickListener(this)
        binding.toAddress.setOnClickListener(this)
        binding.txHash.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.from_address -> {
                txInfo?.apply {
                    val data = ClipData.newPlainText("from", from)
                    clipboardManager?.setPrimaryClip(data)
                    toast(getString(R.string.wallet_transfer_address_copied))
                }
            }

            R.id.to_address -> {
                txInfo?.apply {
                    val data = ClipData.newPlainText("to", to)
                    clipboardManager?.setPrimaryClip(data)
                    toast(getString(R.string.wallet_receive_address_copied))
                }
            }

            R.id.tx_hash -> {
                val data = ClipData.newPlainText("txId", txId)
                clipboardManager?.setPrimaryClip(data)
                toast(getString(R.string.wallet_hash_copied))
            }
        }
    }
}