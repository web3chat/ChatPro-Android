package com.fzm.chat.search.web3

import java.math.BigInteger


data class WCEthereumTransaction(
    val from: String,
    val to: String,
    val gas: String,
    val gasPrice: String?,
    val value: String?,
    val nonce: String?,
    val data: String?
) {
    override fun toString(): String {
        return "WCEthereumTransaction(from='$from', to=$to, nonce=$nonce, gasPrice=$gasPrice, value=$value, data='$data')"
    }
}

data class CreateTran(
    val from: String,
    val gas: BigInteger,
    val gasPrice: BigInteger,
    val input: String?,
    val nonce: Long,
    val to: String,
    val value: BigInteger,
    val leafPosition:Long = 0
)