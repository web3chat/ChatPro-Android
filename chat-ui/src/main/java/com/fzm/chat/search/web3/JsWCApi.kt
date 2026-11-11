package com.fzm.chat.search.web3

import android.app.AlertDialog
import android.text.TextUtils
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fzm.chat.R
import com.fzm.chat.core.MyWallet
import com.fzm.chat.databinding.DialogPwdBinding
import com.fzm.chat.search.web3.GoWallet.Companion.getPrikey
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.wallet.sdk.utils.GoWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.ECKey
import org.jetbrains.anko.toast
import org.json.JSONObject
import org.litepal.LitePal
import org.litepal.extension.find
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import walletapi.Walletapi
import wendu.dsbridge.CompletionHandler
import java.math.BigInteger

class JsWCApi(
    private val webView: WebView,
    private val activity: FragmentActivity,
    private val jsListener: JsListener
) {


    @JavascriptInterface
    fun sendTransaction(
        callbackId: Int,
        chainType: String,
        data: String?,
        from: String,
        to: String

    ) {
        Log.v("pro", "callbackId  === =$callbackId")
        Log.v("pro", "from  === =$from")

    }


    @JavascriptInterface
    fun signTransaction(
        callbackId: Int,
        //BEP-20 代币（如 USDT）通常会通过智能合约进行转账。如果没有目标地址，则为 BNB 转账
        recipient: String?,
        value: String?,
        nonce: String?,
        gasLimit: String?,
        gasPrice: String?,
        payload: String?
    ) {
        try {
            val dValue = if (value == null || value == "undefined") {
                "0"
            } else {
                value
            }

            val dGasPrice = gasPrice ?: "0"
            val limit = gasLimit ?: "1000000"
            val transaction = Web3Transaction(
                if (TextUtils.isEmpty(recipient)) Address.EMPTY else Address(recipient!!),
                null,
                Hex.hexToBigInteger(dValue),
                Hex.hexToBigInteger(dGasPrice, BigInteger.ZERO),
                Hex.hexToBigInteger(limit, BigInteger.ZERO),
                Hex.hexToLong(nonce, -1),
                payload,
                callbackId.toLong()
            )

            webView.post {
                jsListener.onSignTransaction(transaction)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    @JavascriptInterface
    fun signMessage(callbackId: Int, data: String?) {
        webView.post {
            jsListener.onSignMessage(callbackId, data)
        }
    }

    @JavascriptInterface
    fun signPersonalMessage(callbackId: Int, data: String?) {
        webView.post {
            jsListener.onSignPersonalMessage(callbackId, data)
        }

    }


    @JavascriptInterface
    fun requestAccounts(callbackId: Long) {
        webView.post {
            jsListener.onRequestAccounts(callbackId)
        }
    }

    @JavascriptInterface
    fun signTypedMessage(callbackId: Int, data: String?) {
        Log.v("pro", "signTypedMessage === $callbackId, $data")
        webView.post {
        }
    }

    @JavascriptInterface
    fun ethCall(callbackId: Int, recipient: String) {
        try {
            val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
            val json = JSONObject(recipient)
            val defaultBlockParameter: DefaultBlockParameter
            val to = if (json.has("to")) json.getString("to") else ZERO_ADDRESS
            val payload = if (json.has("data")) json.getString("data") else "0x"
            val value = if (json.has("value")) json.getString("value") else null
            val gasLimit = if (json.has("gas")) json.getString("gas") else null
            defaultBlockParameter =
                DefaultBlockParameterName.LATEST //TODO: Take block param from query if present
            val call = Web3Call(
                Address(to),
                defaultBlockParameter,
                payload,
                value,
                gasLimit,
                callbackId.toLong()
            )
            jsListener.onEthCall(call)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun walletAddEthereumChain(callbackId: Int, msgParams: String?) {
        //切换网络回调
        webView.post {
            jsListener.walletAddEthereumChain(callbackId, msgParams)
        }
    }

    @JavascriptInterface
    fun walletSwitchEthereumChain(callbackId: Int, msgParams: String?) {

        webView.post {
            jsListener.walletSwitchEthereumChain(callbackId, msgParams)
        }


    }


    @JavascriptInterface
    fun sign1xData(msg: Any, handler: CompletionHandler<String?>) {
        val map = msg.toString().jsonToMap<String>()
        val content = map["content"]

        val bindingDialog = DialogPwdBinding.inflate(activity.layoutInflater)
        val dialog =
            AlertDialog.Builder(activity).setView(bindingDialog.root).create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
        bindingDialog.ivClose.setOnClickListener {
            dialog.dismiss()
            handler?.complete(toJSONStr("error" to activity.getString(R.string.cancel)))
        }
        bindingDialog.btnOk.setOnClickListener {
            val password = bindingDialog.etInput.text.toString()
            if (password.isEmpty()) {
                activity.toast(activity.getString(R.string.my_wallet_password_tips))
                return@setOnClickListener
            }

            activity.lifecycleScope.launch(Dispatchers.IO) {
                val wallet = LitePal.find<PWallet>(MyWallet.getId())
                wallet?.let {
                    withContext(Dispatchers.Main) {
                        //loading.show()
                    }
                    val check = GoWallet.checkPasswd(password, it.password)
                    if (!check) {
                        withContext(Dispatchers.Main) {
                            activity.toast(activity.getString(R.string.pwd_fail_str))
                            //loading.dismiss()
                        }
                        return@let
                    }
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        val bPassword = GoWallet.encPasswd(password)!!
                        val mnem: String = GoWallet.decMenm(bPassword, wallet.mnem)

                        val hdWallet = Walletapi.newWalletFromMnemonic_v2("BTY", mnem)
                        val address = hdWallet.newAddress_v2(0)
                        val prikey = hdWallet.newKeyPriv(0)
                        val newPriKey = Walletapi.byteTohex(prikey)

                        val signTx = sign(
                            content!!,
                            newPriKey,
                        )
                        handler.complete(toJSONStr("signHash" to signTx, "address" to address))

                    }


                }

            }


        }

    }

    @JavascriptInterface
    fun getAddress(msg: Any, handler: CompletionHandler<String?>) {
        val map = msg.toString().jsonToMap<String>()
        val chain = map["cointype"]
        chain?.let {
            val chain = getChain(it)
            handler.complete(toJSONStr("address" to chain?.address))
        }

    }

    fun getChain(chain: String): Coin? {
        val chains = LitePal.where(
            "pwallet_id=? and chain = ?",
            "${MyWallet.getId()}",
            chain
        ).find<Coin>(true)
        if (chains.isNullOrEmpty()) {
            return null
        }
        return chains[0]

    }


    fun sign(content: String, privateKey: String): String {
        return try {
            val privateKeyBytes = org.bouncycastle.util.encoders.Hex.decode(privateKey)
            val ecKey: ECKey = ECKey.fromPrivate(privateKeyBytes)
            ecKey.signMessage(content)
        } catch (e: java.lang.Exception) {
            throw Exception("BTC生成签名异常")
        }
    }


    fun test1() {
        val address = "1LNEyigJ4TDrcQpgCoUJbBJB3SS9Rk3NF3"
        val privateKey = "bdb98f77cd069fa1f5bf37e11abd15a3efd630e9445a0d1f20d0b78fb44dc4d0"
        val content = "123123"

        val signature: String = sign(content, privateKey)
        println(signature)
    }

}