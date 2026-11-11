package com.fzm.chat.wallet.impl

import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet

/**
 * @author zhengjy
 * @since 2022/01/13
 * Description:
 */
class WalletC {

    var type: Int = 0

    /**
     * 助记词
     */
    var mnemonic: String? = null
        private set

    /**
     * 私钥
     */
    var privateKey: String? = null
        private set

    /**
     * 地址
     */
    var address: String? = null
        private set

    /**
     * 钱包名
     */
    var walletName: String? = null
        private set

    /**
     * 钱包密码
     */
    var password: String? = null
        private set

    /**
     * 用户标识符
     */
    var user: String = ""
        private set
    var phone: String = ""
        private set
    var email: String = ""
        private set

    /**
     * 钱包币种
     */
    val coins: List<Coin>
        get() = _coins ?: emptyList()

    private var _coins: List<Coin>? = null

    companion object {

        fun mnemonicWallet(
            mnemonic: String,
            walletName: String,
            password: String,
            user: String,
            coins: List<Coin>,
            phone:String,
            email:String
        ) = WalletC().apply {
            this.type = PWallet.TYPE_NOMAL
            this.mnemonic = mnemonic
            this.walletName = walletName
            this.password = password
            this.user = user
            this._coins = coins
            this.phone = phone
            this.email = email
        }

        fun privateKeyWallet(
            privateKey: String,
            walletName: String,
            password: String,
            user: String,
            coins: List<Coin>
        ) = WalletC().apply {
            this.privateKey = privateKey
            this.walletName = walletName
            this.password = password
            this.user = user
            this._coins = coins
        }

        fun addressWallet(
            address: String,
            walletName: String,
            password: String,
            user: String,
            coins: List<Coin>
        ) = WalletC().apply {
            this.address = address
            this.walletName = walletName
            this.password = password
            this.user = user
            this._coins = coins
        }
    }
}