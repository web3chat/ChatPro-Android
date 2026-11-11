package com.fzm.chat.search.meta

import org.json.JSONArray

interface Web3InterfaceListener {

    // 注意这里要加外部引号 "\"$chainId\""

    //"eth_requestAccounts", "eth_accounts"
    fun ethAccounts(id: String)
    fun ethChainId(id: String)
    fun netVersion(id: String)
    fun personalSign(id: String,params:JSONArray)
    fun ethSignTypedDataV4(id: String,params:JSONArray)
    fun ethSendTransaction(id: String,params: JSONArray)
    fun walletSwitchEthereumChain(id: String,params: JSONArray)
    fun walletAddEthereumChain(id: String,params: JSONArray)
    fun ethGetTransactionReceipt(id: String,params: JSONArray)
    fun handleReadRpcCall(id: String, method: String, paramsString: String)

}