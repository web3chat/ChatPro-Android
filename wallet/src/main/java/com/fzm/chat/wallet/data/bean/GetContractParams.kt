package com.fzm.chat.wallet.data.bean

import java.io.Serializable

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
data class GetContractParams(
    val cointype: String,
    val address: String,
    val extend_info: ExtendInfo,
) : Serializable {

    companion object {

        fun createContractParams(
            cointype: String,
            address: String,
            extend_info: ExtendInfo,
        ): GetContractParams {
            return GetContractParams(
                cointype,
                address,
                extend_info,
            )
        }
    }
}
data class ExtendInfo(
    val Execer: String,
): Serializable