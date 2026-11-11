package com.fzm.chat.search.web3

import android.util.Log
import com.fzm.chat.core.MyWallet
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.net.UrlConfig
import com.fzm.wallet.sdk.utils.GoWallet
import com.google.gson.Gson
import org.litepal.Operator
import walletapi.*

class GoWallet {
    companion object {
        const val LIVE_KEY_FEE = "live_key_fee"
        const val LOW_GAS_PRICE = 3000000000
        val HTTP_HEADER = "http://"
        val HTTPS_HEADER = "https://"
        const val WEB3_BNB = "https://bsc.publicnode.com"
        const val WEB3_ETH = "https://rpc.flashbots.net"
        const val WEB3_BTY = "https://mainnet.bityuan.com/eth"
        private const val CHAIN_ID_ETH = "eip155:1"
        private const val CHAIN_ID_BNB = "eip155:56"
        private const val CHAIN_ID_BTY = "eip155:2999"

        const val CHAIN_ID_ETH_L = 1L
        const val CHAIN_ID_BNB_L = 56L
        const val CHAIN_ID_BTY_L = 2999L

        //ETH BNB BTY GAS转账都是21000
        const val GAS_OUT: Long = 21000

        const val CHAIN_NET = "chainNet"
        const val NET_ETH = "Ethereum"
        const val NET_BNB = "BNB Smart Chain"
        const val NET_BTY = "BitYuan Mainnet"

        const val LOW: Double = 1.5
        const val MIDDLE: Double = 2.0
        const val HIGH: Double = 3.0

        val CHAIN_MAPS_LL =
            mapOf(
                CHAIN_ID_ETH to CHAIN_ID_ETH_L,
                CHAIN_ID_BNB to CHAIN_ID_BNB_L,
                CHAIN_ID_BTY to CHAIN_ID_BTY_L
            )

        val CHAIN_MAPS =
            mapOf("ETH" to CHAIN_ID_ETH_L, "BNB" to CHAIN_ID_BNB_L, "BTY" to CHAIN_ID_BTY_L)

        val CHAIN_ID_MAPS =
            mapOf(CHAIN_ID_ETH to "ETH", CHAIN_ID_BNB to "BNB", CHAIN_ID_BTY to "BTY")

        val CHAIN_ID_MAPS_L =
            mapOf(CHAIN_ID_ETH_L to "ETH", CHAIN_ID_BNB_L to "BNB", CHAIN_ID_BTY_L to "BTY")

        val CHAIN_ID_MAPS_ADDR =
            mapOf(CHAIN_ID_ETH_L to "ETH", CHAIN_ID_BNB_L to "BNB", CHAIN_ID_BTY_L to "ETH")

        val NET_MAPS =
            mapOf(CHAIN_ID_ETH_L to NET_ETH, CHAIN_ID_BNB_L to NET_BNB, CHAIN_ID_BTY_L to NET_BTY)

        private val gson = Gson()

        private val util = Util()

        private var lastRefreshSessionTime: Long = 0L
        var sessionID: String = ""


        /**
         *  创建助记词
         * @param mnemLangType Int  1 中文  2 英文
         * @return String
         */
        fun createMnem(mnemLangType: Int): String {
            return when (mnemLangType) {
                1 -> Walletapi.newMnemonicString(1, 160)
                2 -> Walletapi.newMnemonicString(0, 128)
                else -> Walletapi.newMnemonicString(1, 160)
            }
        }


        fun getPrikey(chain: String, mnem: String): String {
            try {
                val hdWallet = Walletapi.newWalletFromMnemonic_v2(chain, mnem)
                val prikey = hdWallet.newKeyPriv(0)
                return Walletapi.byteTohex(prikey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return ""
        }

        /**
         *
         * @param chain String   主链
         * @param mnem String    助记词
         * @return HDWallet?
         */
        @JvmStatic
        fun getHDWallet(chain: String, mnem: String): HDWallet? {
            try {
                return Walletapi.newWalletFromMnemonic_v2(chain, mnem)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }

        /**
         * 公钥转地址
         * @param chain String   主链
         * @param pub  String  公钥
         * @return String?
         */
        fun pubToAddr(chain: String, pub: String): String? {
            try {
                return Walletapi.pubToAddress_v2(chain, Walletapi.hexTobyte(pub))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }

        /**
         * 私钥转地址
         * @param chain String   主链
         * @param priv String    私钥
         * @return String
         */
        fun privToAddr(chain: String, priv: String): String? {
            try {
                val pub = Walletapi.privkeyToPub_v2(chain, Walletapi.hexTobyte(priv))
                return Walletapi.pubToAddress_v2(chain, pub)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }

        fun priToPub(chain: String, priv: String): ByteArray? {
            try {
                return Walletapi.privkeyToPub_v2(chain, Walletapi.hexTobyte(priv))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }

        fun byteTohex(byteArray: ByteArray): String {
            return Walletapi.byteTohex(byteArray)
        }

        private var session: WalletSession? = null

        fun setSessionInfo(session: WalletSession) {
            this.session = session
        }

        fun checkSessionID(force: Boolean = false) {
            /*if (System.currentTimeMillis() - lastRefreshSessionTime < 29 * 60 * 1000 && !force) {
                // sessionID半小时过期，提前1分钟刷新
                return
            }
            return try {
                sessionID = Walletapi.getSessionId(session, getUtil(UrlConfig.GO_URL))
                Walletapi.setSessionID(sessionID)
                lastRefreshSessionTime = System.currentTimeMillis()
            } catch (e: Exception) {
                e.printStackTrace()
            }*/
        }







        fun getChainName(chainId: String?): String {
            return when (chainId) {
                CHAIN_ID_ETH -> {
                    "ETH"
                }

                CHAIN_ID_BNB -> {
                    "BNB"
                }

                CHAIN_ID_BTY -> {
                    "BTY"
                }

                else -> "ETH"
            }
        }

        fun getWeb3Url(chainId: String?): String {
            return when (chainId) {
                CHAIN_ID_ETH -> {
                    WEB3_ETH
                }

                CHAIN_ID_BNB -> {
                    WEB3_BNB
                }

                else -> WEB3_ETH
            }
        }

        fun getWeb3UrlL(chainId: Long?): String {
            return when (chainId) {
                CHAIN_ID_ETH_L -> {
                    WEB3_ETH
                }

                CHAIN_ID_BNB_L -> {
                    WEB3_BNB
                }

                CHAIN_ID_BTY_L -> {
                    WEB3_BTY
                }

                else -> WEB3_ETH
            }
        }


        fun getChainNet(index: Int): String {
            return when (index) {
                0 -> NET_BTY
                1 -> NET_ETH
                2 -> NET_BNB
                else -> NET_BTY
            }
        }

        fun getNewUrl(url: String): String {
            val lowUrl = url.lowercase()
            return if (!lowUrl.startsWith(HTTP_HEADER) && !lowUrl.startsWith(HTTPS_HEADER)) {
                "$HTTPS_HEADER$lowUrl"
            } else {
                lowUrl
            }
        }

        fun getChain(chain: String): Coin? {
            val id = MyWallet.getID()
            val chains = Operator.where(
                "pwallet_id=? and chain = ?",
                "$id",
                chain
            ).find(Coin::class.java,true)
            if (chains.isNullOrEmpty()) {
                return null
            }
            return chains[0]

        }

        fun signTran(chain: String, unSignData: String, priv: String): String? {
            try {
                val signData = SignData()
                signData.cointype = chain
                signData.data = Walletapi.stringTobyte(unSignData)
                signData.privKey = priv
                val signRawTransaction =
                    Walletapi.signRawTransaction(signData)
                Log.v("tag", "签名交易: $signRawTransaction")
                return signRawTransaction
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            return null
        }

        fun signTran(chain: String, unSignData: ByteArray, priv: String, addressId: Int): String? {
            try {
                val signData = SignData()
                signData.cointype = chain
                signData.data = unSignData
                signData.privKey = priv
                if (addressId != -1) {
                    signData.addressID = addressId
                }
                val signRawTransaction =
                    Walletapi.signRawTransaction(signData)
                Log.v("tag", "签名交易: $signRawTransaction")
                return signRawTransaction
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
                Log.e("error", "$e")
            }
            return null
        }


        fun sendTran(
            chain: String,
            signData: String,
            tokenSymbol: String,
            util: Util = GoWallet.getUtil(UrlConfig.GO_URL)
        ): String? {
            try {
                GoWallet.checkSessionID()
                val sendTx = WalletSendTx()
                sendTx.cointype = chain
                sendTx.signedTx = signData
                sendTx.tokenSymbol = tokenSymbol
                sendTx.util = util
                val sendRawTransaction =
                    Walletapi.byteTostring(Walletapi.sendRawTransaction(sendTx))
                Log.v("tag", "发送交易: $sendRawTransaction")
                return sendRawTransaction
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            return null
        }
    }
}