package com.fzm.chat.search.web3

import com.fzm.chat.MMkvUtil
import com.google.gson.Gson

open class Constants {
    companion object {
        const val COINS_KEY = "coins_key"
        const val TRAN_STATE_KEY = "tran_state_key"
        const val TRAN_STATE_BTY = "tran_state_bty"
        const val PAGE_LIMIT = 100L
        const val FEE_KEY = "fee_key"
        const val FEE_CUSTOM_POSITION = 33
        const val BTY_ETH_NODE = "https://mainnet.bityuan.com/eth"



        fun setGear(dGear: DGear) {
            val gearStr = Gson().toJson(dGear)
            MMkvUtil.encode(FEE_KEY, gearStr)
        }

        fun getGear(): DGear? {
            val gearStr = MMkvUtil.decodeString(FEE_KEY)
            if(gearStr.isEmpty()) {
                return null
            }
            return Gson().fromJson(gearStr,DGear::class.java)
        }


    }

}