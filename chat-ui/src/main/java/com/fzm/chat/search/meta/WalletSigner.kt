package com.fzm.chat.search.meta

import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthSendTransaction
import java.math.BigInteger

object WalletSigner {

    /**
     * 实现 personal_sign 逻辑
     * @param hexMessage DApp 发来的消息（通常是十六进制字符串）
     * @param privateKey 你的私钥 (带 0x 或不带均可)
     */
    fun signPersonalMessage(hexMessage: String, privateKey: String): String {
        // 1. 加载私钥凭证
        val credentials = Credentials.create(privateKey)

        // 2. 将消息转换为字节数组
        // 有些 DApp 发送的是原始文本，有些是十六进制。这里处理十六进制输入：
        val messageBytes = if (hexMessage.startsWith("0x")) {
            Numeric.hexStringToByteArray(hexMessage)
        } else {
            hexMessage.toByteArray(Charsets.UTF_8)
        }

        // 3. 使用 Web3j 签名（会自动添加 "\x19Ethereum Signed Message:\n" 前缀）
        val signatureData = Sign.signPrefixedMessage(messageBytes, credentials.ecKeyPair)

        // 4. 将签名结果 (r, s, v) 组合成 65 字节的十六进制字符串
        val v = signatureData.v
        val r = signatureData.r
        val s = signatureData.s

        val sig = ByteArray(65)
        System.arraycopy(r, 0, sig, 0, 32)
        System.arraycopy(s, 0, sig, 32, 32)
        sig[64] = v[0]

        return Numeric.toHexString(sig)
    }


    fun sendTransaction(
        to: String,
        data: String,
        valueHex: String,
        gasLimit: BigInteger, // 接收解析后的值
        privateKey: String,
        web3j: Web3j,
        gasPrice: BigInteger,
        chainId:Long
    ): String {
        val credentials = Credentials.create(privateKey)
        val fromAddress = credentials.address

        // 获取 Nonce
        val ethGetTransactionCount = web3j.ethGetTransactionCount(
            fromAddress, DefaultBlockParameterName.PENDING
        ).send()
        val nonce = ethGetTransactionCount.transactionCount

        // 获取 GasPrice
        //val gasPrice = web3j.ethGasPrice().send().gasPrice

        // 解析 Value
        val value = if (valueHex.startsWith("0x")) Numeric.toBigInt(valueHex) else BigInteger(valueHex)

        // 构建交易 (注意：BSC 的 ChainId 是 56)
        val rawTransaction = RawTransaction.createTransaction(
            nonce,
            gasPrice,
            gasLimit, // 使用 DApp 传过来的 Gas Limit
            to,
            value,
            data
        )

        // 签名并广播...
        val signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials)
        val ethSendTransaction = web3j.ethSendRawTransaction(Numeric.toHexString(signedMessage)).send()

        return ethSendTransaction.transactionHash
    }


    // ... 之前的 signPersonalMessage 方法保持不变 ...


}
