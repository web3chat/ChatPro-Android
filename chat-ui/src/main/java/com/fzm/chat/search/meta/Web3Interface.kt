package com.fzm.chat.search.meta

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.fzm.chat.search.DappActivity
import org.json.JSONObject
import java.math.BigInteger
import kotlin.concurrent.thread


class Web3Interface(val activity: DappActivity, val webView: WebView,val web3InterfaceListener: Web3InterfaceListener) {


    @JavascriptInterface
    fun callNative(jsonPayload: String) {
        Log.v("wawawa","jsonPayload == $jsonPayload")
        val payload = JSONObject(jsonPayload)
        val method = payload.getString("method")
        val id = payload.getString("id")
        val params = payload.getJSONArray("params")

        // sendToJs  注意要加外部引号 "\"$chainId\""
        when (method) {
            "eth_requestAccounts", "eth_accounts" -> {
                web3InterfaceListener.ethAccounts(id)
                //val address = "0x6b7E1e936F2C50B62ffA373EfFCeE1F77706e757" // 动态获取
                //val result = "[\"$address\"]"
                //sendToJs(id, result)
            }

            "eth_chainId" -> {
                web3InterfaceListener.ethChainId(id)
                //val chainId = "0x38" // 动态获取，例如来自设置界面
                //sendToJs(id, "\"$chainId\"")
            }

            "net_version" -> {
                web3InterfaceListener.netVersion(id)
                //val networkVersion = "56" // 动态获取
                //sendToJs(id, "\"$networkVersion\"")
            }

            "personal_sign" -> {
                web3InterfaceListener.personalSign(id,params)
                // params[0] 是消息内容, params[1] 是地址
                //val message = params.getString(0)
                //val signature = WalletSigner.signPersonalMessage(message, myPrivateKey)
                //sendToJs(id, "\"$signature\"")
            }
            "eth_signTypedData_v4"->{
                web3InterfaceListener.ethSignTypedDataV4(id,params)
            }

            "eth_sendTransaction" -> {
              web3InterfaceListener.ethSendTransaction(id,params)
            }
            "wallet_switchEthereumChain" -> {
              web3InterfaceListener.walletSwitchEthereumChain(id,params)
            }
            "wallet_addEthereumChain" -> {
              web3InterfaceListener.walletAddEthereumChain(id,params)
            }
            "eth_getTransactionReceipt" -> {
                Log.v("wawawa","回调：eth_getTransactionReceipt")
              web3InterfaceListener.ethGetTransactionReceipt(id,params)
            }

            "eth_call", "eth_estimateGas", "eth_getTransactionCount", "eth_gasPrice", "eth_blockNumber", "eth_getBalance",
            "eth_getBlockByNumber" -> {
                // 这些属于静默查询方法，不需要用户弹窗确认，直接转发给 RPC 节点
                val paramsString = params.toString()
                web3InterfaceListener.handleReadRpcCall(id, method, paramsString)
            }


        }
    }

    private fun sendToJs(id: String, result: String) {
        webView.post {
            // 调用 JS 里的 _onResponse
            webView.evaluateJavascript("window.ethereum._onResponse('$id', $result, null)", null)
        }
    }

}
