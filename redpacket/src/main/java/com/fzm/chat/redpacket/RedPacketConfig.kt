package com.fzm.chat.redpacket

import com.fzm.chat.router.redpacket.ModuleAsset
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.utils.decimalPlaces
import com.fzm.wallet.sdk.utils.fullChain
import com.fzm.wallet.sdk.utils.isCoin
import com.zjy.architecture.util.AbstractConfig

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
object RedPacketConfig {

    private object WalletDev : AbstractConfig("redPacket-dev.properties")
    private object WalletPro : AbstractConfig("redPacket-pro.properties")

    private val config = WalletPro

    /**
     * 红包平行链
     */
    val FULL_CHAIN = config["FULL_CHAIN"]

    /**
     * EVM红包合约地址
     */
    val CONTRACT_ADDRESS = config["CONTRACT_ADDRESS"]

    /**
     * EVM RPC节点地址
     */
    val RPC_URL = config["RPC_URL"]

    /**
     * EVM链ID
     */
    val CHAIN_ID = config["CHAIN_ID"].toLong()

    /**
     * 原生币展示符号（如 BitYuan 主网为 BTY）。
     * 可在 redPacket-*.properties 中设置 NATIVE_TOKEN_SYMBOL 覆盖；未设置时 2999 链默认 BTY，其余默认 ETH。
     */
    val NATIVE_TOKEN_SYMBOL: String
        get() = config["NATIVE_TOKEN_SYMBOL"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (CHAIN_ID) {
                2999L -> "BTY"
                else -> "ETH"
            }

    /**
     * 原生币链上精度，与合约 uint256 最小单位一致（与发红包侧 totalAmount 编码一致）。
     * 可在 properties 中设置 NATIVE_TOKEN_DECIMALS；默认 18。
     */
    val NATIVE_TOKEN_DECIMALS: Int
        get() = config["NATIVE_TOKEN_DECIMALS"]?.toIntOrNull() ?: 18
}

fun Coin.toAsset() = ModuleAsset(
    nickname,
    fullChain,
    isCoin,
    name ?: "",
    balance ?: "",
    decimalPlaces,
    contract_address ?: "",
    platform ?: ""
)