package com.fzm.chat.wallet

import android.content.ContentValues
import android.util.Log
import com.alibaba.fastjson.JSON
import com.fzm.chat.wallet.data.WalletRepository
import com.fzm.wallet.sdk.MnemonicManager
import com.fzm.wallet.sdk.bean.StringResult
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.utils.GoWallet
import com.google.gson.Gson
import com.zjy.architecture.di.rootScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.litepal.LitePal
import walletapi.GsendTx
import walletapi.Walletapi
import java.util.ArrayDeque


class WalletSDK {

    private val walletRepository by rootScope.inject<WalletRepository>()

    private fun loadCoinsFromDb(walletId: String): List<Coin> =
        LitePal.where(
            "pwallet_id = ? and status = ?", walletId, Coin.STATUS_ENABLE.toString()
        ).find(Coin::class.java, true)

    /** 币种关键信息：用于定位余额查询失败/超时时具体是哪个币 */
    private fun Coin.brief(): String =
        "id=$id,name=$name,nickname=$nickname,chain=$chain,platform=$platform,address=$address,contract=$contract_address"

    fun getCoinBalances(
        walletId: String
    ): Flow<List<Coin>> = flow<List<Coin>> collector@ {
        // 先推本地库快照，避免首包要等 receive + getCoinList/链上拉取整轮才第一次 emit（侧栏选币会卡几秒）
        emit(loadCoinsFromDb(walletId))
        coroutineScope {
            val triggers = Channel<Unit>(Channel.CONFLATED)
            launch {
                // 定时轮询只在资产页面真正展示时才触发（见 setBalancePollingEnabled），
                // 页面切到后台/其它 Tab 后不再请求余额与行情接口（如 interface/wallet-coin）
                while (isActive) {
                    if (balancePollingEnabled) triggers.send(Unit)
                    delay(BALANCE_POLL_INTERVAL_MS)
                }
            }
            launch {
                while (isActive) {
                    coinBalanceRefreshChannel.receive()
                    triggers.send(Unit)
                }
            }
            while (isActive) {
                triggers.receive()
                // 双保险：资产页面不在前台时直接丢弃本轮，绝不请求余额/行情接口
                // （Wallet.GetBalance、interface/wallet-coin 都只在这一轮里发）
                if (!balancePollingEnabled) continue
                balanceCycleMutex.withLock {
                    this@collector.emitCoinBalancesCycle(walletId)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 拉取一轮链上余额并 [emit] 到 Flow；与定时/手动触发串行，避免并发写 LitePal。
     */
    private suspend fun FlowCollector<List<Coin>>.emitCoinBalancesCycle(walletId: String) {
        supervisorScope {
            val coins = loadCoinsFromDb(walletId)
            if (coins.isEmpty()) {
                emit(emptyList())
                return@supervisorScope
            } else {
                emit(coins)
            }
            val deferred = ArrayDeque<Deferred<Unit>>()
            Log.d(TAG, "开始刷新余额，共 ${coins.size} 个币种：${coins.joinToString { it.name ?: "" }}")
            for (coin in coins) {
                deferred.add(async(Dispatchers.IO) {
                    try {
                        if (coin.contract_address.isNullOrEmpty() || coin.platform == "wwchain") {
                            // 走钱包 SDK 原生查询（walletapi → 服务端），网络不通会抛 proxyerror
                            Log.d(TAG, "查询余额[native] ${coin.brief()}")
                            // native 查询是阻塞调用，且内部没有超时控制（节点不可达时 i/o timeout 可能很久），
                            // 这里做超时保护：超时则跳过该币，避免一个币把整轮余额刷新卡住
                            val task = async(Dispatchers.IO) { GoWallet.handleBalance(coin) }
                            val balance = withTimeoutOrNull(NATIVE_BALANCE_TIMEOUT_MS) { task.await() }
                            if (balance == null) {
                                Log.w(TAG, "查询余额[native]超时(${NATIVE_BALANCE_TIMEOUT_MS}ms)，跳过 ${coin.brief()}")
                            } else {
                                coin.balance = balance
                                Log.v(TAG, "查询余额[native]完成 name=${coin.name},platform=${coin.platform},balance=${coin.balance}")
                                updateLocalCoin(
                                    ContentValues().apply { put("balance", coin.balance) },
                                    coin.id
                                )
                            }
                        } else {

                            if (coin.platform == "btyerc") {
                                Log.d(TAG, "查询余额[btyerc] ${coin.brief()}")
                                val result = walletRepository.getBalanceBySymbol(
                                    "BTY",
                                    coin.address,
                                    coin.contract_address
                                )
                                if (result.isSucceed()) {
                                    coin.balance = result.data()?.balance
                                    Log.v(TAG, "查询余额[btyerc]完成 name=${coin.name},platform=${coin.platform},balance=${coin.balance}")
                                    updateLocalCoin(
                                        ContentValues().apply { put("balance", coin.balance) },
                                        coin.id
                                    )
                                } else {
                                    Log.w(TAG, "查询余额[btyerc]失败 ${coin.brief()}，err=${result.error()}")
                                }
                            } else {
                                Log.d(TAG, "查询余额[contract] ${coin.brief()}")
                                val result = walletRepository.getBalanceByContract(
                                    coin.chain,
                                    coin.address,
                                    coin.contract_address
                                )
                                if (result.isSucceed()) {
                                    coin.balance = result.data()?.balance
                                    Log.v(TAG, "查询余额[contract]完成 name=${coin.name},chain=${coin.chain},balance=${coin.balance}")
                                    updateLocalCoin(
                                        ContentValues().apply { put("balance", coin.balance) },
                                        coin.id
                                    )
                                } else {
                                    Log.w(TAG, "查询余额[contract]失败 ${coin.brief()}，err=${result.error()}")
                                }
                            }


                        }
                        return@async
                    } catch (e: Exception) {
                        // 原来这里静默吞异常，无法定位是哪个币失败；
                        // native 层打印 "Getbalance err: Post \"<服务端地址>\" ... i/o timeout" 时，
                        // 紧跟这条日志就是对应的币种
                        Log.e(TAG, "查询余额异常 ${coin.brief()}，err=${e.message}", e)
                    }
                })
            }
            val quotationDeferred =
                // 查询资产行情等
                async { walletRepository.getCoinList(coins.map { "${it.name},${it.platform}" }) }
            quotationDeferred.await().dataOrNull()?.also { coinMeta ->
                val coinMap = coins.associateBy { "${it.chain}-${it.name}-${it.platform}" }
                for (meta in coinMeta) {
                    coinMap["${meta.chain}-${meta.name}-${meta.platform}"]?.apply {
                        this.rmb = meta.rmb
                        this.icon = meta.icon
                        this.nickname = meta.nickname
                        this.treaty = meta.treaty
                        this.netId = meta.netId
                        this.optionalName = meta.optionalName
                        this.contract_address = meta.contract_address
                        updateLocalCoin(
                            ContentValues().apply {
                                put("rmb", rmb)
                                put("icon", icon)
                                put("nickname", nickname)
                                put("treaty", treaty)
                                put("netId", netId)
                                put("optionalName", optionalName)
                                put("contract_address", contract_address)
                            },
                            id
                        )
                    }
                }
                emit(coins)
            }
            while (deferred.isNotEmpty()) {
                deferred.poll()?.await()
            }
            emit(coins)
        }
    }


    private fun updateLocalCoin(values: ContentValues, id: Long) {
        LitePal.update(Coin::class.java, values, id)
    }


    //转账
    suspend fun transfer(
        coin: Coin,
        toAddress: String,
        amount: Double,
        fee: Double,
        note: String?,
        password: String
    ): String {
        return withContext(Dispatchers.IO) {
            val result = MnemonicManager.checkPassword(password)
            if (!result) {
                throw Exception("密码输入错误")
            }
            val mnem = MnemonicManager.getMnemonicWords(password)
            val privateKey = coin.getPrivkey(
                if (coin.chain == "BNB" || coin.chain == "POL") "ETH" else coin.chain,
                mnem
            )
                ?: throw Exception("私钥获取失败")
            handleTransfer(coin, toAddress, amount, fee, note, privateKey)
        }
    }


    private var addressId = 0
    protected val gson by lazy { Gson() }
    private suspend fun handleTransfer(
        coin: Coin,
        toAddress: String,
        cAmount: Double,
        cFee: Double,
        note: String?,
        privateKey: String,
    ): String {
        val coinToken = coin.newChain
        val tsy = coinToken.tokenSymbol


        if (coin.chain == "POL") {
            val result = walletRepository.createByContract(
                coinToken.cointype,
                tsy,
                coin.address,
                toAddress,
                cAmount,
                cFee,
                coin.contract_address
            )
            if (result.isSucceed()) {
                val createResult = result.data()
                val createJson = gson.toJson(createResult)
                return signAndSends(
                    "ETH",
                    tsy,
                    createJson,
                    coinToken.cointype,
                    137,
                    coin.address,
                    privateKey
                )
            }
        } else {
            // 构造交易
            if (coin.contract_address.isNullOrEmpty()) {
                //如果需要代扣
                if (coinToken.proxy) {
                    val gsendTx = GsendTx().apply {
                        feepriv = privateKey
                        to = toAddress
                        tokenSymbol = tsy
                        execer = coinToken.exer
                        amount = cAmount
                        txpriv = privateKey
                        //消耗的BTY
                        fee = 0.01
                        //扣的手续费接收地址
                        //tokenFeeAddr = YBF_FEE_ADDR
                        //扣多少手续费
                        //tokenFee = if (it.platform == IPConfig.YBF_CHAIN) YBF_TOKEN_FEE else TOKEN_FEE
                        coinsForFee = false

                        //feeAddressID是收比特元的手续费地址格式，txAddressID是当前用户地址格式
                        feeAddressID = if (coin.address.startsWith("0x")) 2 else 0
                        txAddressID = if (coin.address.startsWith("0x")) 2 else 0
                    }
                    val gsendTxResp = Walletapi.coinsTxGroup(gsendTx)
                    GoWallet.sendTran(coinToken.cointype, gsendTxResp.signedTx, tsy)
                    return gsendTxResp.txId
                } else {
                    val rawTx = GoWallet.createTran(
                        coinToken.cointype,
                        coin.address,
                        toAddress,
                        cAmount,
                        cFee,
                        note ?: "",
                        tsy
                    )
                    val stringResult = JSON.parseObject(rawTx, StringResult::class.java)
                    val createRawResult: String = stringResult.result ?: ""
                    return signAndSends(
                        coinToken.cointype,
                        tsy,
                        createRawResult,
                        coinToken.cointype,
                        -1,
                        coin.address,
                        privateKey
                    )


                }


            } else {
                val result = walletRepository.createByContract(
                    coinToken.cointype,
                    tsy,
                    coin.address,
                    toAddress,
                    cAmount,
                    cFee,
                    coin.contract_address
                )
                if (result.isSucceed()) {
                    val createResult = result.data()
                    val createJson = gson.toJson(createResult)
                    if (coin.platform == "wwchain") {
                        return signAndSends(
                            coin.chain,
                            tsy,
                            createJson,
                            GoWallet.PARA,
                            5188,
                            coin.address,
                            privateKey
                        )
                    } else if (coin.platform == "btyerc") {
                        return signAndSends(
                            coin.chain,
                            tsy,
                            createJson,
                            coinToken.cointype,
                            2999,
                            coin.address,
                            privateKey
                        )
                    } else {
                        return signAndSends(
                            coinToken.cointype,
                            tsy,
                            createJson,
                            coinToken.cointype,
                            -1,
                            coin.address,
                            privateKey
                        )
                    }
                }
            }
        }



        return ""

    }

    private fun signAndSends(
        coinType: String,
        tokenSymbol: String,
        createRawResult: String,
        sendChain: String,
        chainId: Int,
        address: String,
        privateKey: String
    ): String {

        //签名交易
        addressId = if (address.startsWith("0x")) 2 else 0
        val signTx = GoWallet.signTran(
            coinType, Walletapi.stringTobyte(createRawResult), privateKey, addressId, chainId
        ) ?: throw Exception("签名交易失败")

        // 发送交易
        val sendTx = GoWallet.sendTran(sendChain, signTx, tokenSymbol)
        val sendResult = gson.fromJson(sendTx, StringResult::class.java)
        val txId = sendResult.result
        if (sendResult == null) {
            throw Exception("获取结果失败，请至区块链浏览器查看")
        }
        if (!sendResult.error.isNullOrEmpty()) {
            throw Exception(sendResult.error)
        }
        if (txId.isNullOrEmpty()) {
            throw Exception("获取结果失败，请至区块链浏览器查看")
        }
        return txId
    }

    companion object {
        /** 余额/行情查询日志 TAG，过滤：adb logcat -s WalletBalance */
        private const val TAG = "WalletBalance"

        /** 定时拉取间隔；即时刷新见 [requestCoinBalanceRefresh]。 */
        private const val BALANCE_POLL_INTERVAL_MS = 2_000L

        /** native 余额查询超时（毫秒）：节点不可达时避免整轮余额刷新被卡住 */
        private const val NATIVE_BALANCE_TIMEOUT_MS = 8_000L

        /**
         * 资产页面是否正在展示。
         *
         * 只有资产列表页在前台时才允许定时轮询余额/行情，避免用户切到其它页面
         * （如“消息”）后依然每 2 秒请求一次 interface/wallet-coin。
         */
        @Volatile
        private var balancePollingEnabled = false

        /** 资产页面进入/离开前台时调用 */
        fun setBalancePollingEnabled(enabled: Boolean) {
            balancePollingEnabled = enabled
        }

        private val balanceCycleMutex = Mutex()

        /** 与 Kotlin 1.5 / coroutines 1.5 下 [Flow.collect] 在 [flow] 体内的解析冲突，改用 Channel。 */
        private val coinBalanceRefreshChannel = Channel<Unit>(Channel.CONFLATED)

        /** 资产列表快照刷新请求（如添加资产页 +/- 币种后返回时触发，由共享 WalletViewModel 监听） */
        val assetSnapshotRefreshRequests = Channel<Unit>(Channel.CONFLATED)

        fun requestCoinBalanceRefresh() {
            coinBalanceRefreshChannel.offer(Unit)
        }

        fun requestAssetSnapshotRefresh() {
            assetSnapshotRefreshRequests.offer(Unit)
        }
    }
}