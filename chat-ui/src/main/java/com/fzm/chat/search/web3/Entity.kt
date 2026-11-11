package com.fzm.chat.search.web3

import java.math.BigInteger

data class Gear(
    val speed: String,
    val gas: BigInteger,
    //gwei
    val gasPrice: BigInteger,
    val gasStr: String,
    val gasPriceStr: String,
    val newGas: String,
    val content: String,
)
data class DGear(
    val gas: BigInteger,
    val gasPrice: BigInteger,
    val position:Int
)