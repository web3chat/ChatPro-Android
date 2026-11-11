package com.fzm.chat.redpacket.evm

import org.web3j.abi.EventEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.Log
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * 对应 contract-api22.md **Claimed** 事件：`id`(indexed), `user`(indexed), `amount`
 * 领取明细优先用合约 view `getClaimRecords`（见 RedPacketEvmContract）；本类仅在回退或需交易哈希/区块时间时用于 **eth_getLogs**。
 */
data class EvmClaimRecord(
    val userAddress: String,
    val amountWei: BigInteger,
    val blockNumber: BigInteger,
    val transactionHash: String,
    val logIndex: BigInteger,
)

object RedPacketClaimedLogs {

    private const val CLAIMED_EVENT = "Claimed(uint256,address,uint256)"

    fun claimedEventTopic(): String = EventEncoder.buildEventSignature(CLAIMED_EVENT)

    /**
     * 查询某红包 id 下所有 Claimed 日志（不限领取人）。
     */
    fun fetchClaimedLogs(web3j: Web3j, contractAddress: String, packetId: BigInteger): List<EvmClaimRecord> {
        val filter = EthFilter(
            DefaultBlockParameterName.EARLIEST,
            DefaultBlockParameterName.LATEST,
            contractAddress
        )
        filter.addSingleTopic(claimedEventTopic())
        filter.addOptionalTopics(Numeric.toHexString(Numeric.toBytesPadded(packetId, 32)))

        val response = web3j.ethGetLogs(filter).send()
        if (response.hasError()) {
            throw Exception(response.error?.message ?: "eth_getLogs failed")
        }
        val logs: List<Log> = (response.getResult() ?: emptyList()) as List<Log>
        return logs.mapNotNull { decodeClaimedLog(it) }
            .sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
    }

    private fun decodeClaimedLog(log: Log): EvmClaimRecord? {
        val topics = log.topics ?: return null
        if (topics.size < 3) return null
        val userTopic = topics[2]
        val addrHex = run {
            val h = Numeric.cleanHexPrefix(userTopic)
            if (h.length < 40) return null
            Numeric.prependHexPrefix(h.takeLast(40))
        }
        val data = log.data ?: return null
        val amountWei = try {
            Numeric.toBigInt(data)
        } catch (_: Exception) {
            return null
        }
        val blockNumber = log.blockNumber ?: return null
        val txHash = log.transactionHash ?: return null
        val logIndex = log.logIndex ?: BigInteger.ZERO
        return EvmClaimRecord(
            userAddress = addrHex.lowercase(),
            amountWei = amountWei,
            blockNumber = blockNumber,
            transactionHash = txHash,
            logIndex = logIndex
        )
    }

    /**
     * 批量解析区块时间戳（秒），减少重复 RPC。
     */
    fun blockTimestampSeconds(
        web3j: Web3j,
        blockNumbers: Set<BigInteger>,
        cache: MutableMap<BigInteger, Long>
    ) {
        for (bn in blockNumbers) {
            if (cache.containsKey(bn)) continue
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(bn), false).send().block
                ?: continue
            val ts = block.timestamp ?: continue
            val sec = when {
                ts.bitLength() > 62 -> System.currentTimeMillis() / 1000
                else -> ts.toLong()
            }
            cache[bn] = sec
        }
    }
}
