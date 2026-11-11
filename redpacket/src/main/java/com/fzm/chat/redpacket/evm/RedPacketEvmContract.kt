package com.fzm.chat.redpacket.evm

import com.fzm.chat.redpacket.RedPacketConfig
import com.fzm.chat.redpacket.data.bean.RedPacketInfo
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * RedPacketUpgradeable 合约 eth_call / 编码辅助（与 contract-api22.md ABI 一致）
 */
object RedPacketEvmContract {

    private val zeroAddr = "0x0000000000000000000000000000000000000000"

    fun isEnabled(): Boolean {
        val addr = RedPacketConfig.CONTRACT_ADDRESS?.lowercase()?.trim() ?: return false
        return addr.isNotEmpty() && addr != zeroAddr
    }

    /** getRedPacket(uint256 id) view */
    fun encodeGetRedPacket(id: BigInteger): String {
        val function = Function(
            "getRedPacket",
            listOf(Uint256(id)),
            getRedPacketOutputs()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * 与 contract-api.md 4.1 一致，共 **10** 个返回值（每槽 32 字节 → 共 320 字节 / 640 个 hex 字符）。
     * 多声明 1 个类型会导致 FunctionReturnDecoder 读越界（如 length=640, index=704）。
     */
    private fun getRedPacketOutputs(): List<TypeReference<out Type<*>>> = listOf(
        object : TypeReference<Address>() {}, // creator
        object : TypeReference<Address>() {}, // token
        object : TypeReference<Uint8>() {}, // RedPacketType ptype
        object : TypeReference<Uint256>() {}, // totalAmount
        object : TypeReference<Uint256>() {}, // totalCount
        object : TypeReference<Uint256>() {}, // claimedCount
        object : TypeReference<Uint256>() {}, // expireAt
        object : TypeReference<Uint256>() {}, // whitelistCount
        object : TypeReference<Uint256>() {}, // amountPerShare
        object : TypeReference<Bool>() {}, // withdrawn
    )

    /** claim(uint256 id) returns (uint256) */
    fun encodeClaim(id: BigInteger): String {
        val function = Function(
            "claim",
            listOf(Uint256(id)),
            listOf(object : TypeReference<Uint256>() {})
        )
        return FunctionEncoder.encode(function)
    }

    /** withdraw(uint256 id) */
    fun encodeWithdraw(id: BigInteger): String {
        val function = Function(
            "withdraw",
            listOf(Uint256(id)),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /** isClaimed(uint256 id, address user) view */
    fun encodeIsClaimed(id: BigInteger, userAddress: String): String {
        val function = Function(
            "isClaimed",
            listOf(Uint256(id), Address(userAddress)),
            listOf(object : TypeReference<Bool>() {})
        )
        return FunctionEncoder.encode(function)
    }

    /** isInWhitelist(uint256 id, address account) view */
    fun encodeIsInWhitelist(id: BigInteger, account: String): String {
        val function = Function(
            "isInWhitelist",
            listOf(Uint256(id), Address(account)),
            listOf(object : TypeReference<Bool>() {})
        )
        return FunctionEncoder.encode(function)
    }

    /** isExpired(uint256 id) view */
    fun encodeIsExpired(id: BigInteger): String {
        val function = Function(
            "isExpired",
            listOf(Uint256(id)),
            listOf(object : TypeReference<Bool>() {})
        )
        return FunctionEncoder.encode(function)
    }

    /** getClaimRecords(uint256 id) view returns (ClaimRecord[])，ClaimRecord = (address user, uint256 amount) */
    fun encodeGetClaimRecords(id: BigInteger): String {
        val function = Function(
            "getClaimRecords",
            listOf(Uint256(id)),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /** getClaimRecordCount(uint256 id) view */
    fun encodeGetClaimRecordCount(id: BigInteger): String {
        val function = Function(
            "getClaimRecordCount",
            listOf(Uint256(id)),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * getMyRedPacketIds(address,bool,uint256,uint256,string) view returns uint256[]
     * month 为 0 表示整年（见 contract-api22.md 4.14）
     */
    fun encodeGetMyRedPacketIds(
        account: String,
        asReceiver: Boolean,
        year: BigInteger,
        month: BigInteger,
        symbolFilter: String
    ): String {
        val function = Function(
            "getMyRedPacketIds",
            listOf(
                Address(account),
                Bool(asReceiver),
                Uint256(year),
                Uint256(month),
                Utf8String(symbolFilter)
            ),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    /** getRedPacketTokenSymbol(uint256 id) view returns string */
    fun encodeGetRedPacketTokenSymbol(id: BigInteger): String {
        val function = Function(
            "getRedPacketTokenSymbol",
            listOf(Uint256(id)),
            emptyList()
        )
        return FunctionEncoder.encode(function)
    }

    fun ethCall(web3j: Web3j, from: String, data: String): String {
        val to = RedPacketConfig.CONTRACT_ADDRESS
            ?: throw IllegalStateException("CONTRACT_ADDRESS not configured")
        // Web3j 4.x：读合约用 createEthCallTransaction(from, to, data)，无 createEthCall
        val response = web3j.ethCall(
            Transaction.createEthCallTransaction(from, to, data),
            DefaultBlockParameterName.LATEST
        ).send()
        if (response.hasError()) {
            throw Exception(response.error?.message ?: "eth_call failed")
        }
        return response.value ?: "0x"
    }

    /**
     * 解析 getRedPacket 返回值；无效 id（creator 为 0）返回 null
     */
    fun decodeGetRedPacket(hexValue: String, packetIdStr: String): RedPacketInfo? {
        if (hexValue.isEmpty() || hexValue == "0x") return null
        val function = Function(
            "getRedPacket",
            listOf(Uint256(BigInteger.ZERO)),
            getRedPacketOutputs()
        )
        val decoded = try {
            FunctionReturnDecoder.decode(hexValue, function.outputParameters)
        } catch (e: Exception) {
            return null
        } ?: return null
        if (decoded.size < 10) return null
        val creatorAddr = decoded[0] as Address
        val creatorHex = Numeric.prependHexPrefix(Numeric.cleanHexPrefix(creatorAddr.toString()))
        if (creatorHex.equals(zeroAddr, ignoreCase = true)) return null

        val tokenAddr = decoded[1] as Address
        val token = Numeric.prependHexPrefix(Numeric.cleanHexPrefix(tokenAddr.toString()))
        // ABI uint8 为 0–255：用十进制字符串转 Int，避免 BigInteger 的 byteValue/intValue/intValueExact 在 Lint 中的 API 差异
        val ptype = ((decoded[2] as Uint8).value as BigInteger).toString().toInt()
        val totalAmount = (decoded[3] as Uint256).value
        val totalCount = (decoded[4] as Uint256).value
        val claimedCount = (decoded[5] as Uint256).value
        val expireAt = (decoded[6] as Uint256).value
        val whitelistCount = (decoded[7] as Uint256).value
        // decoded[8] = amountPerShare（固定红包每份金额；随机红包为 0）
        val withdrawn = (decoded[9] as Bool).value

        val nowSec = System.currentTimeMillis() / 1000
        val expired = expireAt.toLong() <= nowSec
        val status = when {
            withdrawn -> 3
            claimedCount >= totalCount -> 2
            expired -> 4
            else -> 1
        }

        val typeField = if (ptype == 1) 1 else 2

        val amountLong = when {
            totalAmount.bitLength() > 62 -> Long.MAX_VALUE
            else -> totalAmount.toLong()
        }

        val isNative = token.equals(zeroAddr, ignoreCase = true)
        return RedPacketInfo(
            packetId = packetIdStr,
            addr = creatorHex,
            type = typeField,
            assetExec = if (isNative) "evm" else "token",
            assetSymbol = if (isNative) RedPacketConfig.NATIVE_TOKEN_SYMBOL else "",
            assetAddress = if (isNative) "" else token,
            amount = amountLong,
            size = totalCount.toInt().coerceAtLeast(0),
            remain = (totalCount - claimedCount).toInt().coerceAtLeast(0),
            toAddr = emptyList(),
            status = status,
            signPubkey = "",
            remark = "",
            createTime = 0L,
            updatedTime = 0L,
            expiresTime = expireAt.toLong(),
            decimalPlaces = if (isNative) RedPacketConfig.NATIVE_TOKEN_DECIMALS else 18,
            txIndex = whitelistCount.toString()
        )
    }

    fun decodeBoolResult(hexValue: String): Boolean {
        if (hexValue.isEmpty() || hexValue == "0x") return false
        val function = Function(
            "isExpired",
            listOf(Uint256(BigInteger.ZERO)),
            listOf(object : TypeReference<Bool>() {})
        )
        val decoded = FunctionReturnDecoder.decode(hexValue, function.outputParameters) ?: return false
        return (decoded[0] as Bool).value
    }

    fun decodeClaimAmount(hexValue: String): BigInteger {
        val function = Function(
            "claim",
            listOf(Uint256(BigInteger.ZERO)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val decoded = FunctionReturnDecoder.decode(hexValue, function.outputParameters)
            ?: return BigInteger.ZERO
        return (decoded[0] as Uint256).value
    }

    /** 单槽 uint256 返回值（如 getClaimRecordCount） */
    fun decodeUint256Word(hexValue: String): BigInteger {
        val hex = Numeric.cleanHexPrefix(hexValue)
        if (hex.isEmpty()) return BigInteger.ZERO
        val word = hex.takeLast(64).padStart(64, '0')
        return BigInteger(word, 16)
    }

    /**
     * ABI 动态数组 uint256[]（如 getMyRedPacketIds 返回值）
     */
    fun decodeUint256Array(hexValue: String): List<BigInteger> {
        val hex = Numeric.cleanHexPrefix(hexValue)
        if (hex.length < 128) return emptyList()
        val offsetBytes = BigInteger(hex.substring(0, 64), 16).toInt()
        val start = offsetBytes * 2
        if (hex.length < start + 64) return emptyList()
        val n = BigInteger(hex.substring(start, start + 64), 16).toInt()
        if (n <= 0 || n > 100_000) return emptyList()
        val out = ArrayList<BigInteger>(n)
        var pos = start + 64
        repeat(n) {
            if (hex.length < pos + 64) return out
            out.add(BigInteger(hex.substring(pos, pos + 64), 16))
            pos += 64
        }
        return out
    }

    /**
     * getClaimRecords 返回的 (address,uint256)[] 动态数组
     */
    fun decodeClaimRecords(hexValue: String): List<Pair<String, BigInteger>> {
        val hex = Numeric.cleanHexPrefix(hexValue)
        if (hex.length < 128) return emptyList()
        val offsetBytes = BigInteger(hex.substring(0, 64), 16).toInt()
        val start = offsetBytes * 2
        if (hex.length < start + 64) return emptyList()
        val n = BigInteger(hex.substring(start, start + 64), 16).toInt()
        if (n < 0 || n > 100_000) return emptyList()
        val out = ArrayList<Pair<String, BigInteger>>(n)
        var pos = start + 64
        repeat(n) {
            if (hex.length < pos + 128) return out
            val addrWord = hex.substring(pos, pos + 64)
            val addr = Numeric.prependHexPrefix(addrWord.takeLast(40))
            val amount = BigInteger(hex.substring(pos + 64, pos + 128), 16)
            out.add(addr.lowercase() to amount)
            pos += 128
        }
        return out
    }

    /** ABI 动态 string（如 getRedPacketTokenSymbol） */
    fun decodeAbiString(hexValue: String): String {
        val hex = Numeric.cleanHexPrefix(hexValue)
        if (hex.length < 128) return ""
        val offsetBytes = BigInteger(hex.substring(0, 64), 16).toInt()
        val start = offsetBytes * 2
        if (hex.length < start + 64) return ""
        val len = BigInteger(hex.substring(start, start + 64), 16).toInt()
        if (len <= 0) return ""
        val dataStart = start + 64
        val byteCount = len.coerceAtMost((hex.length - dataStart) / 2)
        if (byteCount <= 0) return ""
        val dataHex = hex.substring(dataStart, dataStart + byteCount * 2)
        return try {
            String(Numeric.hexStringToByteArray(dataHex), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
