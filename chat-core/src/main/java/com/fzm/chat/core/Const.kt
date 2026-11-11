package com.fzm.chat.core

import android.util.Log
import com.fzm.chat.core.utils.MMkvUtil
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.BuildConfig


fun logDebug(log: String) {
    if (BuildConfig.DEBUG) {
        Log.v("pro", log)
    }
}

class MyWallet {
    companion object {
        //当前钱包的ID
        private const val ID_KEY = "id_key"
        const val ID_DEFAULT: Long = -1
        private var id: Long = ID_DEFAULT

        fun getId(): Long {
            return if (id != ID_DEFAULT) {
                id
            } else {
                MMkvUtil.decodeLong(ID_KEY)
            }
        }

        fun getID(): Long {
            val id = getId()
            val nId = if (id == 0L) {
                BWallet.get().getCurrentWallet()?.id!!
            } else {
                id
            }
            return nId
        }

        fun setId(id: Long) {
            MMkvUtil.encode(ID_KEY, id)
            Companion.id = id
        }
    }
}


