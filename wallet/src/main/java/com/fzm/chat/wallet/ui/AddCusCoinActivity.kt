package com.fzm.chat.wallet.ui

import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.MyWallet
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.wallet.R
import com.fzm.chat.wallet.data.WalletRepository
import com.fzm.chat.wallet.data.bean.ExtendInfo
import com.fzm.chat.wallet.databinding.ActivityAddCusCoinBinding
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zjy.architecture.di.rootScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.anko.toast
import org.litepal.LitePal
import org.litepal.extension.find

/**
 * @author zhengjy
 * @since 2020/12/16
 * Description:
 */
@Route(path = WalletModule.ADD_CUS_COINS)
class AddCusCoinActivity : BizActivity() {


    @JvmField
    @Autowired
    var bindType = 0


    private val binding by init { ActivityAddCusCoinBinding.inflate(layoutInflater) }
    private val repository by rootScope.inject<WalletRepository>()

    private var curChain = ""
    private var curChainUI = ""
    override val root: View
        get() = binding.root

    override val darkStatusColor: Boolean = true

    override fun initView() {
        ARouter.getInstance().inject(this)
    }

    override fun initData() {

    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
        val bd = BottomSheetDialog(this)
        bd.setContentView(R.layout.select_chain)
        val llBnb = bd.findViewById<LinearLayout>(R.id.ll_bnb)
        val llETH = bd.findViewById<LinearLayout>(R.id.ll_eth)
        val llBTY = bd.findViewById<LinearLayout>(R.id.ll_bty)
        val llPOL = bd.findViewById<LinearLayout>(R.id.ll_pol)
        val rlCancel = bd.findViewById<RelativeLayout>(R.id.rl_cancel)
        when (W.appType) {
            WConfig.APP_CHAT33PRO -> {
                llBnb?.visibility = View.VISIBLE
                llETH?.visibility = View.VISIBLE
                llBTY?.visibility = View.VISIBLE
                llPOL?.visibility = View.GONE
                curChain = "ETH"
                curChainUI = "BTY"
            }

            WConfig.APP_CIP -> {
                llBnb?.visibility = View.VISIBLE
                llPOL?.visibility = View.VISIBLE
                llETH?.visibility = View.GONE
                llBTY?.visibility = View.GONE
                curChain = "BNB"
                curChainUI = "BNB"
            }

            else -> {
                llBnb?.visibility = View.VISIBLE
                llETH?.visibility = View.VISIBLE
                llBTY?.visibility = View.VISIBLE
                llPOL?.visibility = View.GONE
                curChain = "ETH"
                curChainUI = "BTY"
            }
        }
        llBnb?.setOnClickListener {
            curChain = "BNB"
            curChainUI = "BNB"
            selectChain(curChainUI)
            bd.dismiss()
        }
        llETH?.setOnClickListener {
            curChain = "ETH"
            curChainUI = "ETH"
            selectChain(curChainUI)
            bd.dismiss()
        }
        llBTY?.setOnClickListener {
            curChain = "ETH"
            curChainUI = "BTY"
            selectChain(curChainUI)
            bd.dismiss()
        }
        llPOL?.setOnClickListener {
            curChain = "POL"
            curChainUI = "POL"
            selectChain(curChainUI)
            bd.dismiss()
        }
        rlCancel?.setOnClickListener {
            bd.dismiss()
        }
        selectChain(curChainUI)
        binding.llSelChain.setOnClickListener {
            bd.show()
        }
        binding.etContractAddress.addTextChangedListener { text ->
            Log.v("cat", "=====$text")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val res = repository.getContract(curChain, "", ExtendInfo(text.toString()))
                    val data = res.data()
                    withContext(Dispatchers.Main) {
                        binding.etSymbol.setText(data.symbol)
                        binding.etDecimal.setText(data.decimals)
                        binding.etSymbol.isEnabled = false
                        binding.etDecimal.isEnabled = false
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }


            }
        }


        binding.btnOkAdd.setOnClickListener {
            val contractAddress = binding.etContractAddress.text.toString()
            val symbol = binding.etSymbol.text.toString()
            val decimal = binding.etDecimal.text.toString()
            if (contractAddress.isEmpty()) {
                toast(getString(R.string.wallet_hint_input_contract_address))
                return@setOnClickListener
            }
            if (symbol.isEmpty()) {
                toast(getString(R.string.wallet_hint_input_symbol))
                return@setOnClickListener
            }
            if (decimal.isEmpty()) {
                toast(getString(R.string.wallet_hint_input_decimal))
                return@setOnClickListener
            }
            if (!isEngNumeric(contractAddress)) {
                toast(getString(R.string.wallet_error_contract_address_format))
                return@setOnClickListener
            }
            if (!isEnglishLetters(symbol)) {
                toast(getString(R.string.wallet_error_symbol_format))
                return@setOnClickListener
            }
            if (!isNumeric(decimal)) {
                toast(getString(R.string.wallet_error_decimal_format))
                return@setOnClickListener
            }

            val id = MyWallet.getID()
            val pwallet = LitePal.find<PWallet>(id, true)

            val fcoin = pwallet.coinList.find { it.chain == curChain }

            val coin = Coin()
            coin.name = symbol
            coin.contract_address = contractAddress
            coin.chain = curChain
            coin.address = fcoin?.address
            if (curChainUI == "BTY") {
                coin.platform = "btyerc"
                coin.treaty = "2"
            } else {
                coin.platform = coin.chain.lowercase()
            }
            coin.nickname = curChainUI
            coin.status = Coin.STATUS_ENABLE
            coin.setpWallet(pwallet)
            coin.save()
            finish()

        }
    }

    fun isEnglishLetters(str: String): Boolean {
        return Regex("^[a-zA-Z]+$").matches(str)
    }

    fun isNumeric(str: String): Boolean {
        // 正则表达式，用于匹配整数和小数
        val pattern = Regex("^-?\\d+(\\.\\d+)?$")
        return pattern.matches(str)
    }

    fun isEngNumeric(str: String): Boolean {
        return str.matches(Regex("^[a-zA-Z0-9]+$"))
    }

    /**
     * 更新所选网络文案，选中后颜色由占位灰变为深色
     */
    private fun selectChain(uiName: String) {
        binding.tvChain.text = uiName
        binding.tvChain.setTextColor(binding.tvChain.context.getColor(R.color.biz_text_grey_dark))
    }


}