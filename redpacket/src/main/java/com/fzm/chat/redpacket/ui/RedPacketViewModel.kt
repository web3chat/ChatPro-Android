package com.fzm.chat.redpacket.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.biz.base.AppConst
import com.fzm.chat.core.chain.waitForChainResult
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.ChatTarget
import com.fzm.chat.core.data.bean.Option
import com.fzm.chat.core.data.bean.chains
import com.fzm.chat.core.data.bean.isGroup
import com.fzm.chat.core.data.bean.isSuccess
import com.fzm.chat.core.data.execer
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.toMessagePO
import com.fzm.chat.core.data.po.*
import com.fzm.chat.core.logic.MessageSender
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.net.source.TransactionSource
import com.fzm.chat.core.repo.GroupRepository
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.utils.mapResult
import com.fzm.chat.core.utils.mul
import com.fzm.chat.redpacket.RedPacketConfig
import com.fzm.chat.redpacket.data.RedPacketRepository
import com.fzm.chat.redpacket.evm.RedPacketClaimedLogs
import com.fzm.chat.redpacket.evm.RedPacketEvmContract
import com.fzm.chat.redpacket.data.bean.*
import com.fzm.chat.redpacket.data.bean.RedPacketInfo.Companion.FAKE_PACKET
import com.fzm.chat.redpacket.exception.RedPacketFallbackException
import com.fzm.chat.redpacket.exception.RedPacketStateException
import com.fzm.chat.router.redpacket.ModuleAsset
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.db.entity.Coin
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import com.zjy.architecture.util.logI
import dtalk.biz.Biz
import dtalk.biz.msg.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.litepal.Operator
import org.web3j.abi.EventEncoder
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.MnemonicUtils
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Convert
import org.web3j.utils.Numeric
import walletapi.ChainClient
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 「指定领取人」钱包收款地址解析结果
 *
 * @param addresses    可用于合约白名单的收款地址（0x 开头，EVM 地址）
 * @param missingNames 尚未同步钱包收款地址、无法加入白名单的成员昵称
 */
data class ReceiverResolveResult(
    val addresses: List<String>,
    val missingNames: List<String>
)

/**
 * @author zhengjy
 * @since 2021/08/25
 * Description:
 */
class RedPacketViewModel(
    private val repository: RedPacketRepository,
    private val groupRepo: GroupRepository,
    val delegate: LoginDelegate,
    private val manager: ContactManager,
    private val transaction: TransactionSource,
    private val messageSender: MessageSender,
    private val client: ChainClient,
) : LoadingViewModel() {

    companion object {
        const val INITIAL_DELAY = 2000L
        const val QUERY_PERIOD = 800L
        private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
        private val PACKET_ID_DAY_SCALE = BigInteger.valueOf(1_000_000L)
        private const val EVM_STAT_PAGE = 20

        /** 筛选币种时全量求和的最大 id 数；超出则跳过求和（避免 N×多次 ethCall 卡死记录页） */
        private const val EVM_SUM_MAX_IDS = 64

        /** 与旧版 `60 × 3000ms` 量级相当的总轮询休眠预算，避免慢链上更早超时 */
        private const val RECEIPT_POLL_INITIAL_MS = 450L
        private const val RECEIPT_POLL_MAX_MS = 3000L
        private const val RECEIPT_POLL_MAX_ATTEMPTS = 72

        /**
         * 领取 claim / 退回 withdraw / 发红包 create：回执轮询更密，缩短「等待交易确认」体感。
         * 发红包仍必须等到 receipt（[parseRedPacketCreatedEvent] 从日志解析 packetId，聊天消息依赖该 id）。
         */
        private const val USER_RECEIPT_POLL_INITIAL_MS = 100L
        private const val USER_RECEIPT_POLL_MAX_MS = 2000L

        /** 合约发红包 Loading 步骤，与 [SendRedPacketActivity] 文案对应 */
        const val SEND_UI_PREPARE = 1
        const val SEND_UI_VALIDATE_AMOUNT = 11
        const val SEND_UI_CHECK_ALLOWANCE = 12
        const val SEND_UI_APPROVE_WAIT = 13
        const val SEND_UI_CREATE_TX = 14
        const val SEND_UI_WAIT_RECEIPT = 2
        const val SEND_UI_SEND_MSG = 3
        const val SEND_UI_DONE = 6

        /** PMM 发红包时与钱包转账页一致的 Gas Price（10 Gwei） */
        private val PMM_GAS_PRICE_WEI: BigInteger =
            Convert.toWei("10", Convert.Unit.GWEI).toBigInteger()

        /** PMM 创建红包合约调用的 Gas Limit（与转账自定义一致） */
        private val PMM_CREATE_REDPACKET_GAS_LIMIT = BigInteger.valueOf(6_687_779L)

        /** PMM 授权交易 Gas Limit（approve 一般低于创建红包） */
        private val PMM_APPROVE_GAS_LIMIT = BigInteger.valueOf(300_000L)

        private const val PMM_ASSET_SYMBOL = "PMM"
    }

    private fun isPmmSymbol(symbol: String?) =
        symbol?.equals(PMM_ASSET_SYMBOL, ignoreCase = true) == true

    private fun isPmmRedPacketAsset(moduleAsset: ModuleAsset?) = isPmmSymbol(moduleAsset?.symbol)

    private fun shouldUsePmmGasPrice(moduleAsset: ModuleAsset?, pmmHintSymbol: String?) =
        isPmmRedPacketAsset(moduleAsset) || isPmmSymbol(pmmHintSymbol)

    /** 与 getMyRedPacketIds 筛选条件一致，用于分页复用同一批 id */
    private var evmStatisticIdsCache: List<BigInteger> = emptyList()
    private var evmStatisticCacheKey: String = ""

    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    private val walletService by route<WalletService>(WalletModule.SERVICE)

    private val _packetAssets by lazy { MutableLiveData<List<ModuleAsset>>() }
    val packetAsset: LiveData<List<ModuleAsset>>
        get() = _packetAssets

    private val _packetInfo by lazy { MutableLiveData<RedPacketInfo>() }
    val packetInfo: LiveData<RedPacketInfo>
        get() = _packetInfo

    private val _receiveList by lazy { MutableLiveData<List<ReceiveInfo>>() }
    val receiveList: LiveData<List<ReceiveInfo>>
        get() = _receiveList

    private val _myReceiveRecord by lazy { MutableLiveData<ReceiveInfo>() }
    val myReceiveRecord: LiveData<ReceiveInfo>
        get() = _myReceiveRecord

    private val _statisticInfo by lazy { MutableLiveData<StatisticInfo>() }
    val statisticInfo: LiveData<StatisticInfo>
        get() = _statisticInfo

    private val _backPacketResult by lazy { MutableLiveData<String>() }
    val backPacketResult: LiveData<String>
        get() = _backPacketResult

    private val _sendResult by lazy { MutableLiveData<String>() }
    val sendResult: LiveData<String>
        get() = _sendResult

    private val _sendError by lazy { MutableLiveData<Throwable>() }
    val sendError: LiveData<Throwable>
        get() = _sendError

    private val _receiveResult by lazy { MutableLiveData<String>() }
    val receiveResult: LiveData<String>
        get() = _receiveResult

    private val _receiveError by lazy { MutableLiveData<Throwable>() }
    val receiveError: LiveData<Throwable>
        get() = _receiveError

    private val _sendState by lazy { MutableLiveData<Int>() }
    val sendState: LiveData<Int>
        get() = _sendState

    private val _receiveState by lazy { MutableLiveData<Int>() }
    val receiveState: LiveData<Int>
        get() = _receiveState

    private val _browserUrl = MutableLiveData<String>()
    val browserUrl: LiveData<String>
        get() = _browserUrl

    init {
        //client.setCfg(RedPacketConfig.FULL_CHAIN.execer(""), RedPacketConfig.FULL_CHAIN.execer("none"), ChatConfig.NO_BALANCE_PRIVATE_KEY)
    }

    fun hasPassword() = delegate.preference.hasChatPassword()


    fun receiveRedPacket(params: ReceiveRedPacketParams, message: ChatMessage) {
        launch {
            _receiveState.value = 1
            flow {
                if (System.currentTimeMillis() - message.datetime < 10_000) {
                    // 如果是10s内刚发的红包，则不确认红包状态，直接抢红包
                    emit(Result.Success(FAKE_PACKET))
                } else {
                    emit(repository.getRedPacketInfo(params.packetId))
                }
            }.mapResult { packetInfo ->
                val record = repository.getReceiveRecord(delegate.getAddress() ?: "", params.packetId)
                if (record.isSucceed() && !record.data().isEmpty) {
                    throw RedPacketStateException(5, "红包已领取")
                }
                if (packetInfo.isExpired) {
                    throw RedPacketStateException(4, "红包已过期")
                }
                when (packetInfo.status) {
                    1 -> {
                        _receiveState.value = 2
                        val result = repository.receiveRedPacket(params)
                        if (result.isSucceed()) {
                            _receiveState.value = 3
                            waitForChainResult(INITIAL_DELAY, QUERY_PERIOD, 20, checker = { tx -> tx.isSuccess }) {
                                transaction.queryTransaction(result.data())
                            }
                        } else {
                            throw result.error()
                        }
                    }
                    2 -> throw RedPacketStateException(2, "红包已领完")
                    // 如果是已退回状态，则说明已经过期
                    3 -> throw RedPacketStateException(3, "红包已退回")
                    else -> throw RedPacketStateException(packetInfo.status, "未知的红包状态")
                }
            }.mapResult {
                val record = repository.getReceiveRecord(delegate.getAddress() ?: "", params.packetId)
                if (!record.isSucceed()) {
                    Result.Error(RedPacketFallbackException("红包记录查询失败"))
                }
                _receiveState.value = 4
                val result = repository.withDrawRedPacket(message.msg.fullExec, message.msg.exec!!.execer("redpacket"), message.msg.symbol!!, record.data().amount)
                if (result.isSucceed()) {
                    _receiveState.value = 5
                    waitForChainResult(INITIAL_DELAY, QUERY_PERIOD, checker = { tx -> tx.isSuccess }) {
                        transaction.queryTransaction(result.data())
                    }
                } else {
                    throw result.error()
                }
            }.mapResult({ RedPacketFallbackException("红包领取成功，未能提取到钱包：${it.message}") }) {
                message.msg.state = 5
                MessageSubscription.onMessage(Option.UPDATE_CONTENT, message)
                database.messageDao().insert(message.toMessagePO())
                packetInfo
            }.catch {
                _receiveState.value = 6
                if (it is RedPacketStateException && it.state == 5) {
                    // 红包已领取
                    message.msg.state = 5
                    MessageSubscription.onMessage(Option.UPDATE_CONTENT, message)
                    database.messageDao().insert(message.toMessagePO())
                }
                _receiveError.value = it
            }.collect {
                _receiveState.value = 6
                _receiveResult.value = params.packetId
            }
        }
    }

    fun getRedPacketAssets() {
        launch {
            _packetAssets.value =
                walletService?.getModuleAssets(delegate.getAddress() ?: "") ?: emptyList()
        }
    }

    fun getRedPacketInfo(packetId: String) {
        launch {
            loading(true)
            try {
                val result = withContext(Dispatchers.IO) {
                    if (RedPacketEvmContract.isEnabled()) {
                        try {
                            val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))
                            val id = BigInteger(packetId.trim())
                            val raw = RedPacketEvmContract.ethCall(
                                web3j,
                                ZERO_ADDRESS,
                                RedPacketEvmContract.encodeGetRedPacket(id)
                            )
                            var info = RedPacketEvmContract.decodeGetRedPacket(raw, packetId)
                                ?: return@withContext Result.Error(ApiException("链上未找到该红包"))
                            // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到保持为空）
                            if (info.assetExec == "token" && info.assetSymbol.isEmpty()) {
                                resolveEvmTokenSymbol(web3j, id, info)?.let { info = info.copy(assetSymbol = it) }
                            }
                            info.sender = manager.getUserInfo(info.addr, true)
                            Result.Success(info)
                        } catch (e: NumberFormatException) {
                            Result.Error(ApiException("无效的红包编号"))
                        } catch (e: Exception) {
                            Result.Error(ApiException(e.message ?: "链上查询失败"))
                        }
                    } else {
                        val r = repository.getRedPacketInfo(packetId)
                        r.dataOrNull()?.also {
                            it.sender = manager.getUserInfo(it.addr, true)
                        }
                        r
                    }
                }
                when (result) {
                    is Result.Success -> _packetInfo.value = result.data()
                    is Result.Error -> logI("getRedPacketInfo failed: ${result.error().message}")
                }
            } finally {
                loading(false)
            }
        }
    }

    fun getMyReceiveRecord(packetId: String) {
        if (RedPacketEvmContract.isEnabled()) {
            launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        loadMyEvmReceiveRecord(packetId)
                    }
                    _myReceiveRecord.value = info
                } catch (e: Exception) {
                    logI("getMyReceiveRecord EVM: ${e.message}")
                    _myReceiveRecord.value = ReceiveInfo.EMPTY
                }
            }
            return
        }
        request<ReceiveInfo>(loading = false) {
            onRequest {
                repository.getReceiveRecord(delegate.getAddress() ?: "", packetId)
            }
            onSuccess {
                _myReceiveRecord.value = it
            }
        }
    }

    fun getReceiveList(packetId: String, receiveId: String) {
        if (RedPacketEvmContract.isEnabled()) {
            launch {
                try {
                    val list = withContext(Dispatchers.IO) {
                        if (receiveId.isNotEmpty()) {
                            emptyList()
                        } else {
                            loadEvmReceiveList(packetId)
                        }
                    }
                    _receiveList.value = list
                } catch (e: Exception) {
                    logI("getReceiveList EVM: ${e.message}")
                    _receiveList.value = emptyList()
                }
            }
            return
        }
        request<List<ReceiveInfo>>(loading = false) {
            onRequest {
                val result = repository.getReceiveDetail(packetId, receiveId, AppConst.PAGE_SIZE)
                result.dataOrNull()?.onEach {
                    it.receiver = manager.getUserInfo(it.addr, true)
                }
                result
            }
            onSuccess {
                _receiveList.value = it
            }
        }
    }

    /**
     * 当前钱包可能用于匹配 Claimed 日志里 `user` 的 EVM 地址（小写）。
     * 1) 领/发红包时用过的地址（[UserPreference.RED_PACKET_EVM_ADDRESS]，最准）
     * 2) 默认密码下的助记词派生（与发红包/领红包交易一致）
     * 3) 若用户仅设置了密聊密码、[UserPreference.getMnemonicStringWithDefaultPwd] 为空，则从钱包已落库的 Coin 中取 0x 地址（与 Dapp/钱包展示一致）
     */
    private suspend fun candidateEvmAddressesForCurrentWallet(): List<String> {
        val set = linkedSetOf<String>()
        val cached = delegate.preference.RED_PACKET_EVM_ADDRESS
        if (cached.isNotBlank()) {
            set.add(cached.trim().lowercase())
        }
        delegate.preference.getMnemonicStringWithDefaultPwd()?.let { m ->
            deriveEthCredentials(m).address?.lowercase()?.let { set.add(it) }
        }
        try {
            val coins = BWallet.get().getAllCoins() ?: emptyList<Coin>()
            for (c in coins) {
                val a = c.address
                if (!a.isNullOrBlank() && a.length == 42 && a.startsWith("0x", ignoreCase = true)) {
                    set.add(a.trim().lowercase())
                }
            }
        } catch (e: Exception) {
            logI("读取钱包 Coin 失败: ${e.message}")
        }
        // 4) 直接查钱包数据库里的 Coin 地址（Dapp/交易记录页用的就是这份数据），
        //    BWallet 需要初始化/解锁才能用，这里不保证，所以直接查 Coin 表
        set.addAll(walletCoinAddresses())
        logI("红包候选 EVM 地址: $set")
        return set.toList()
    }

    /**
     * 直接查钱包数据库里所有 Coin 的 0x 地址
     *
     * 与 Dapp 页 `GoWallet.getChain(chain)?.address`、交易记录页展示的是同一份数据（LitePal 的 Coin 表），
     * 不依赖 [BWallet] 是否已初始化。
     */
    private fun walletCoinAddresses(): List<String> {
        return try {
            val id = MyWallet.getID()
            val coins = Operator.where("pwallet_id=?", "$id").find(Coin::class.java, true)
            coins?.mapNotNull { coin ->
                val a = coin.address
                if (!a.isNullOrBlank() && a.length == 42 && a.startsWith("0x", ignoreCase = true)) {
                    a.trim().lowercase()
                } else {
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            logI("查询钱包数据库地址失败: ${e.message}")
            emptyList()
        }
    }

    private fun weiToScaledAmount(wei: BigInteger, tokenDecimals: Int): Long {
        val scale = AppConfig.AMOUNT_SCALE.toBigInteger()
        val div = BigInteger.TEN.pow(tokenDecimals)
        if (div.signum() == 0) return 0L
        val v = wei.multiply(scale).divide(div)
        return when {
            v.signum() == 0 -> 0L
            v.bitLength() > 62 -> Long.MAX_VALUE
            else -> v.toLong()
        }
    }

    /** 钱包币种列表缓存（按合约地址匹配代币名用） */
    private var walletAssetsCache: List<ModuleAsset>? = null

    private suspend fun walletAssets(): List<ModuleAsset> {
        walletAssetsCache?.let { return it }
        val list = walletService?.getModuleAssets(delegate.getAddress() ?: "") ?: emptyList()
        if (list.isNotEmpty()) walletAssetsCache = list
        return list
    }

    /**
     * 解析 EVM 红包的币种名（ERC20）：
     * ① 红包合约 `getRedPacketTokenSymbol(id)`
     * ② 钱包币种列表按代币合约地址匹配
     *
     * 解析不到返回 null（上层兜底显示「Token」）。
     */
    private suspend fun resolveEvmTokenSymbol(
        web3j: Web3j,
        id: BigInteger,
        packetInfo: RedPacketInfo
    ): String? {
        if (packetInfo.assetSymbol.isNotEmpty()) return packetInfo.assetSymbol
        if (packetInfo.assetExec == "evm") return RedPacketConfig.NATIVE_TOKEN_SYMBOL
        // ① 红包合约的 getRedPacketTokenSymbol
        try {
            val symRaw = RedPacketEvmContract.ethCall(
                web3j,
                ZERO_ADDRESS,
                RedPacketEvmContract.encodeGetRedPacketTokenSymbol(id)
            )
            val sym = RedPacketEvmContract.decodeAbiString(symRaw)
            if (sym.isNotEmpty()) return sym
        } catch (_: Exception) {
        }
        val token = packetInfo.assetAddress
        if (token.isEmpty()) return null
        // ② 钱包币种列表按合约地址匹配
        walletAssets().firstOrNull { it.contractAddress.equals(token, ignoreCase = true) }
            ?.symbol?.takeIf { it.isNotBlank() }?.let { return it }
        // 不再链上查代币合约 symbol()，解析不到交给上层兜底
        return null
    }

    /** 展示用币种名：真实币种优先，解析不到兜底「Token」/ 原生币种 */
    private suspend fun evmSymbolForDisplay(
        web3j: Web3j,
        id: BigInteger,
        packetInfo: RedPacketInfo
    ): String {
        return resolveEvmTokenSymbol(web3j, id, packetInfo) ?: evmDisplaySymbol(packetInfo)
    }

    private fun evmDisplaySymbol(packetInfo: RedPacketInfo): String {
        if (packetInfo.assetSymbol.isNotEmpty()) return packetInfo.assetSymbol
        return if (packetInfo.assetExec == "evm") RedPacketConfig.NATIVE_TOKEN_SYMBOL else "Token"
    }

    private suspend fun loadEvmReceiveList(packetId: String): List<ReceiveInfo> {
        val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))
        val id = BigInteger(packetId.trim())
        val contract = RedPacketConfig.CONTRACT_ADDRESS ?: return emptyList()
        val raw = RedPacketEvmContract.ethCall(
            web3j, ZERO_ADDRESS, RedPacketEvmContract.encodeGetRedPacket(id)
        )
        val packetInfo = RedPacketEvmContract.decodeGetRedPacket(raw, packetId) ?: return emptyList()
        // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到兜底「Token」）
        val symbol = evmSymbolForDisplay(web3j, id, packetInfo)
        val claimHex = try {
            RedPacketEvmContract.ethCall(
                web3j,
                ZERO_ADDRESS,
                RedPacketEvmContract.encodeGetClaimRecords(id)
            )
        } catch (_: Exception) {
            "0x"
        }
        val claims = RedPacketEvmContract.decodeClaimRecords(claimHex)
        if (claims.isNotEmpty()) {
            val daySec = dayIndexToCreateTimeSeconds(id)
            val decimals = packetInfo.decimalPlaces
            return claims.mapIndexed { index, p ->
                ReceiveInfo(
                    addr = p.first,
                    amount = weiToScaledAmount(p.second, decimals),
                    assetExec = packetInfo.assetExec,
                    assetSymbol = symbol,
                    createTime = daySec,
                    failMessage = null,
                    receiveId = "${index}_evm_claim",
                    receiveHash = null,
                    status = 1,
                    decimalPlaces = decimals,
                    packetId = packetId
                ).apply {
                    receiver = manager.getUserInfo(addr, true)
                }
            }
        }
        val cnt = try {
            RedPacketEvmContract.decodeUint256Word(
                RedPacketEvmContract.ethCall(
                    web3j,
                    ZERO_ADDRESS,
                    RedPacketEvmContract.encodeGetClaimRecordCount(id)
                )
            )
        } catch (_: Exception) {
            BigInteger.ZERO
        }
        if (cnt.signum() == 0) return emptyList()
        val records = RedPacketClaimedLogs.fetchClaimedLogs(web3j, contract, id)
        if (records.isEmpty()) return emptyList()
        val blockSet = records.map { it.blockNumber }.toSet()
        val cache = mutableMapOf<BigInteger, Long>()
        RedPacketClaimedLogs.blockTimestampSeconds(web3j, blockSet, cache)
        val decimals = packetInfo.decimalPlaces
        val daySec = dayIndexToCreateTimeSeconds(id)
        return records.mapIndexed { index, r ->
            ReceiveInfo(
                addr = r.userAddress,
                amount = weiToScaledAmount(r.amountWei, decimals),
                assetExec = packetInfo.assetExec,
                assetSymbol = symbol,
                createTime = cache[r.blockNumber] ?: daySec,
                failMessage = null,
                receiveId = "${index}_${r.transactionHash}_${r.logIndex}",
                receiveHash = r.transactionHash,
                status = 1,
                decimalPlaces = decimals,
                packetId = packetId
            ).apply {
                receiver = manager.getUserInfo(addr, true)
            }
        }
    }

    private suspend fun loadMyEvmReceiveRecord(packetId: String): ReceiveInfo {
        val candidates = candidateEvmAddressesForCurrentWallet()
        if (candidates.isEmpty()) return ReceiveInfo.EMPTY
        val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))
        val id = BigInteger(packetId.trim())
        val contract = RedPacketConfig.CONTRACT_ADDRESS ?: return ReceiveInfo.EMPTY
        val raw = RedPacketEvmContract.ethCall(
            web3j, ZERO_ADDRESS, RedPacketEvmContract.encodeGetRedPacket(id)
        )
        val packetInfo = RedPacketEvmContract.decodeGetRedPacket(raw, packetId) ?: return ReceiveInfo.EMPTY
        // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到兜底「Token」）
        val symbol = evmSymbolForDisplay(web3j, id, packetInfo)
        val claimHex = try {
            RedPacketEvmContract.ethCall(
                web3j,
                ZERO_ADDRESS,
                RedPacketEvmContract.encodeGetClaimRecords(id)
            )
        } catch (_: Exception) {
            "0x"
        }
        val claims = RedPacketEvmContract.decodeClaimRecords(claimHex)
        val fromClaims = claims.firstOrNull { (u, _) ->
            candidates.any { it.equals(u, ignoreCase = true) }
        }
        if (fromClaims != null) {
            val (addr, wei) = fromClaims
            val decimals = packetInfo.decimalPlaces
            return ReceiveInfo(
                addr = addr,
                amount = weiToScaledAmount(wei, decimals),
                assetExec = packetInfo.assetExec,
                assetSymbol = symbol,
                createTime = dayIndexToCreateTimeSeconds(id),
                failMessage = null,
                receiveId = "0_evm_claim",
                receiveHash = null,
                status = 1,
                decimalPlaces = decimals,
                packetId = packetId
            ).apply {
                receiver = manager.getUserInfo(addr, true)
            }
        }
        val records = RedPacketClaimedLogs.fetchClaimedLogs(web3j, contract, id)
        val mine = records.lastOrNull { r ->
            candidates.any { it.equals(r.userAddress, ignoreCase = true) }
        } ?: return ReceiveInfo.EMPTY
        val cache = mutableMapOf<BigInteger, Long>()
        RedPacketClaimedLogs.blockTimestampSeconds(web3j, setOf(mine.blockNumber), cache)
        val decimals = packetInfo.decimalPlaces
        return ReceiveInfo(
            addr = mine.userAddress,
            amount = weiToScaledAmount(mine.amountWei, decimals),
            assetExec = packetInfo.assetExec,
            assetSymbol = symbol,
            createTime = cache[mine.blockNumber] ?: (System.currentTimeMillis() / 1000),
            failMessage = null,
            receiveId = "0_${mine.transactionHash}_${mine.logIndex}",
            receiveHash = mine.transactionHash,
            status = 1,
            decimalPlaces = decimals,
            packetId = packetId
        ).apply {
            receiver = manager.getUserInfo(addr, true)
        }
    }

    private fun dayIndexToCreateTimeSeconds(packetId: BigInteger): Long {
        val dayIndex = packetId.divide(PACKET_ID_DAY_SCALE)
        if (dayIndex.bitLength() > 62) return 0L
        return dayIndex.toLong() * 86400L
    }

    private fun parseStatisticMonth(raw: String?): Int {
        if (raw.isNullOrEmpty()) return 0
        val parts = raw.split("_")
        if (parts.size >= 2) return parts[1].toIntOrNull() ?: 0
        return raw.toIntOrNull() ?: 0
    }

    /**
     * 查询「我」参与（收到/发出）的红包 id 列表
     *
     * ⚠️ 当前钱包可能有多个 0x 地址（助记词派生 + 钱包已落库的 Coin 地址），领红包、
     * 发红包用的可能是其中任意一个。只按第一个地址查询会出现「红包确实抢到了，但记录页为空」，
     * 所以这里按所有候选地址查询后合并去重。
     *
     * @throws Exception 所有地址都查询失败时抛出（让上层提示「红包记录查询失败」，而不是当成「没有记录」）
     */
    private suspend fun queryMyEvmRedPacketIds(
        web3j: Web3j,
        accounts: List<String>,
        asReceiver: Boolean,
        year: Int,
        month: Int,
        symbolFilter: String
    ): List<BigInteger> {
        val ids = linkedSetOf<BigInteger>()
        var anySuccess = false
        for (account in accounts) {
            try {
                val raw = RedPacketEvmContract.ethCall(
                    web3j,
                    ZERO_ADDRESS,
                    RedPacketEvmContract.encodeGetMyRedPacketIds(
                        account,
                        asReceiver,
                        BigInteger.valueOf(year.toLong()),
                        BigInteger.valueOf(month.toLong()),
                        symbolFilter
                    )
                )
                ids.addAll(RedPacketEvmContract.decodeUint256Array(raw))
                anySuccess = true
            } catch (e: Exception) {
                logI("getMyRedPacketIds($account) 失败: ${e.message}")
            }
        }
        if (!anySuccess) {
            throw RedPacketFallbackException("链上查询失败")
        }
        return ids.sortedDescending()
    }

    private suspend fun loadEvmStatisticInfo(map: Map<String, String>): StatisticInfo {
        val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))
        val accounts = candidateEvmAddressesForCurrentWallet()
        val operation = map["operation"]?.toIntOrNull() ?: 1
        if (accounts.isEmpty()) {
            // 不静默返回空列表：否则和「没有记录」看起来一样，无法排查
            throw RedPacketFallbackException("未获取到钱包 0x 地址")
        }
        val asReceiver = operation == 2
        val year = map["year"]?.toIntOrNull() ?: 1970
        val month = parseStatisticMonth(map["month"])
        val symbolFilter = map["assetSymbol"]?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        val cacheKey = "${operation}_${year}_${month}_$symbolFilter"
        val indexCursor = map["receiveId"] ?: map["txIndex"] ?: ""
        val ids: List<BigInteger> = if (indexCursor.isEmpty()) {
            queryMyEvmRedPacketIds(web3j, accounts, asReceiver, year, month, symbolFilter).also {
                evmStatisticIdsCache = it
                evmStatisticCacheKey = cacheKey
            }
        } else {
            if (evmStatisticCacheKey != cacheKey) {
                queryMyEvmRedPacketIds(web3j, accounts, asReceiver, year, month, symbolFilter).also {
                    evmStatisticIdsCache = it
                    evmStatisticCacheKey = cacheKey
                }
            } else {
                evmStatisticIdsCache
            }
        }
        logI("红包记录查询 accounts=${accounts.size}个 year=$year month=$month symbol='$symbolFilter' -> ids=${ids.size}")
        val startIndex = if (indexCursor.isEmpty()) {
            0
        } else {
            val lastId = indexCursor.removePrefix("evm_").trim()
            val idx = ids.indexOfFirst { it.toString() == lastId }
            if (idx < 0) 0 else idx + 1
        }
        val pageIds = ids.drop(startIndex).take(EVM_STAT_PAGE)
        val assetSym = map["assetSymbol"]?.trim()?.takeIf { it.isNotEmpty() }
        val sumAll: Long = if (assetSym.isNullOrEmpty()) {
            -1L
        } else if (ids.size > EVM_SUM_MAX_IDS) {
            -1L
        } else {
            if (asReceiver) {
                sumEvmReceiveScaled(web3j, ids, accounts, assetSym)
            } else {
                sumEvmSendScaled(web3j, ids, assetSym)
            }
        }
        return if (asReceiver) {
            val rows = coroutineScope {
                pageIds.map { pid ->
                    async(Dispatchers.IO) {
                        buildEvmReceiveStatisticRow(web3j, accounts, pid.toString(), assetSym)
                    }
                }.awaitAll().filterNotNull()
            }
            StatisticInfo(
                totalCount = ids.size,
                sum = sumAll,
                count = ids.size,
                operation = operation,
                receiveRecords = rows.onEach {
                    it.sender = manager.getUserInfo(it.fromAddr, true)
                },
                redPackets = null
            )
        } else {
            val rows = coroutineScope {
                pageIds.map { pid ->
                    async(Dispatchers.IO) {
                        buildEvmSendStatisticRow(web3j, pid.toString(), assetSym)
                    }
                }.awaitAll().filterNotNull()
            }
            StatisticInfo(
                totalCount = ids.size,
                sum = sumAll,
                count = ids.size,
                operation = operation,
                receiveRecords = null,
                redPackets = rows.onEach {
                    it.sender = manager.getUserInfo(it.addr, true)
                }
            )
        }
    }

    private suspend fun buildEvmReceiveStatisticRow(
        web3j: Web3j,
        evmAccounts: List<String>,
        packetId: String,
        assetSymFilter: String?
    ): ReceiveInfo? {
        val id = BigInteger(packetId.trim())
        val raw = RedPacketEvmContract.ethCall(
            web3j,
            ZERO_ADDRESS,
            RedPacketEvmContract.encodeGetRedPacket(id)
        )
        val info = RedPacketEvmContract.decodeGetRedPacket(raw, packetId) ?: return null
        // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到兜底「Token」）
        val sym = evmSymbolForDisplay(web3j, id, info)
        if (assetSymFilter != null && !sym.equals(assetSymFilter, ignoreCase = true)) {
            return null
        }
        val claimHex = try {
            RedPacketEvmContract.ethCall(
                web3j,
                ZERO_ADDRESS,
                RedPacketEvmContract.encodeGetClaimRecords(id)
            )
        } catch (_: Exception) {
            "0x"
        }
        val mine = RedPacketEvmContract.decodeClaimRecords(claimHex).firstOrNull { (u, _) ->
            evmAccounts.any { it.equals(u, ignoreCase = true) }
        } ?: return null
        val decimals = info.decimalPlaces
        return ReceiveInfo(
            addr = mine.first,
            amount = weiToScaledAmount(mine.second, decimals),
            assetExec = info.assetExec,
            assetSymbol = sym,
            createTime = dayIndexToCreateTimeSeconds(id),
            failMessage = null,
            receiveId = "evm_$packetId",
            receiveHash = null,
            status = 1,
            decimalPlaces = decimals,
            packetId = packetId
        ).apply {
            fromAddr = info.addr
            type = if (info.type == 1) ChatConst.RED_PACKET_LUCKY else 2
        }
    }

    private suspend fun buildEvmSendStatisticRow(
        web3j: Web3j,
        packetId: String,
        assetSymFilter: String?
    ): RedPacketInfo? {
        val id = BigInteger(packetId.trim())
        val raw = RedPacketEvmContract.ethCall(
            web3j,
            ZERO_ADDRESS,
            RedPacketEvmContract.encodeGetRedPacket(id)
        )
        val info = RedPacketEvmContract.decodeGetRedPacket(raw, packetId) ?: return null
        // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到兜底「Token」）
        val sym = evmSymbolForDisplay(web3j, id, info)
        if (assetSymFilter != null && !sym.equals(assetSymFilter, ignoreCase = true)) {
            return null
        }
        return info.copy(
            createTime = dayIndexToCreateTimeSeconds(id),
            txIndex = "evm_$packetId",
            assetSymbol = sym
        )
    }

    private suspend fun sumEvmReceiveScaled(
        web3j: Web3j,
        ids: List<BigInteger>,
        evmAccounts: List<String>,
        assetSym: String
    ): Long {
        var sumBi = BigInteger.ZERO
        for (id in ids) {
            val raw = try {
                RedPacketEvmContract.ethCall(
                    web3j,
                    ZERO_ADDRESS,
                    RedPacketEvmContract.encodeGetRedPacket(id)
                )
            } catch (_: Exception) {
                continue
            }
            val info = RedPacketEvmContract.decodeGetRedPacket(raw, id.toString()) ?: continue
            // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到兜底「Token」）
            if (!evmSymbolForDisplay(web3j, id, info).equals(assetSym, ignoreCase = true)) continue
            val claimHex = try {
                RedPacketEvmContract.ethCall(
                    web3j,
                    ZERO_ADDRESS,
                    RedPacketEvmContract.encodeGetClaimRecords(id)
                )
            } catch (_: Exception) {
                continue
            }
            val mine = RedPacketEvmContract.decodeClaimRecords(claimHex).firstOrNull { (u, _) ->
                evmAccounts.any { it.equals(u, ignoreCase = true) }
            } ?: continue
            val scaled = weiToScaledAmount(mine.second, info.decimalPlaces)
            sumBi = sumBi.add(BigInteger.valueOf(scaled))
            if (sumBi.bitLength() > 62) return Long.MAX_VALUE
        }
        return sumBi.toLong()
    }

    private suspend fun sumEvmSendScaled(
        web3j: Web3j,
        ids: List<BigInteger>,
        assetSym: String
    ): Long {
        var sumBi = BigInteger.ZERO
        for (id in ids) {
            val raw = try {
                RedPacketEvmContract.ethCall(
                    web3j,
                    ZERO_ADDRESS,
                    RedPacketEvmContract.encodeGetRedPacket(id)
                )
            } catch (_: Exception) {
                continue
            }
            val info = RedPacketEvmContract.decodeGetRedPacket(raw, id.toString()) ?: continue
            // 币种名：合约 symbol() → 钱包币种匹配 → 代币合约 symbol()（解析不到兜底「Token」）
            if (!evmSymbolForDisplay(web3j, id, info).equals(assetSym, ignoreCase = true)) continue
            sumBi = sumBi.add(BigInteger.valueOf(info.amount))
            if (sumBi.bitLength() > 62) return Long.MAX_VALUE
        }
        return sumBi.toLong()
    }

    fun getStatisticRecordList(map: Map<String, String>) {
        if (RedPacketEvmContract.isEnabled()) {
            launch {
                loading(true)
                try {
                    val info = withContext(Dispatchers.IO) {
                        loadEvmStatisticInfo(map)
                    }
                    _statisticInfo.value = info
                } catch (e: Exception) {
                    logI("getStatisticRecordList EVM: ${e.message}")
                    val op = map["operation"]?.toIntOrNull() ?: 1
                    // 不再静默返回空列表：查询失败要能看到原因（否则界面上和「没有记录」看起来一样）
                    _receiveError.value = RedPacketFallbackException(
                        "红包记录查询失败：${e.message ?: e.javaClass.simpleName}"
                    )
                    _statisticInfo.value = if (op == 2) {
                        StatisticInfo(0, -1, 0, op, emptyList(), null)
                    } else {
                        StatisticInfo(0, -1, 0, op, null, emptyList())
                    }
                } finally {
                    loading(false)
                }
            }
            return
        }
        request<StatisticInfo>(loading = true) {
            onRequest {
                val result = repository.getStatisticRecordList(map)
                result.dataOrNull()?.receiveRecords?.onEach {
                    it.sender = manager.getUserInfo(it.fromAddr, true)
                }
                result
            }
            onSuccess {
                _statisticInfo.value = it
            }
        }
    }

    fun backRedPacket(redPacket: RedPacketInfo) {
        launch {
            loading(false)
            flow {
                emit(repository.backRedPacket(redPacket.packetId))
            }.mapResult {
                // 等待区块链确认结果
                waitForChainResult(INITIAL_DELAY, QUERY_PERIOD, 20, checker = { tx -> tx.isSuccess }) {
                    transaction.queryTransaction(it)
                }
            }.mapResult { result ->
                var frozen = 0L
                // 获取可被退回金额
                result.receipt.logs?.forEach {
                    if (it["tyName"] == "LogExecActive") {
                        val map = (it["log"] as? Map<String, Any?>)?.get("current") as? Map<String, Any?>
                        frozen = (map?.get("balance") as? String?)?.toLong()?:0L
                    }
                }
                if (frozen == 0L) {
                    Result.Error(RedPacketFallbackException("可退回余额不足"))
                } else {
                    Result.Success(frozen)
                }
            }.mapResult {
                val packetExec = RedPacketConfig.FULL_CHAIN.execer("redpacket")
                val assetExec = RedPacketConfig.FULL_CHAIN.execer(redPacket.assetExec)
                val result = repository.withDrawRedPacket(assetExec, packetExec, redPacket.assetSymbol, it)
                if (result.isSucceed()) {
                    waitForChainResult(INITIAL_DELAY, QUERY_PERIOD, checker = { tx -> tx.isSuccess }) {
                        transaction.queryTransaction(result.data())
                    }
                } else {
                    throw result.error()
                }
            }.catch {
                dismiss()
                _receiveError.value = it
            }.collect {
                dismiss()
                _backPacketResult.value = redPacket.packetId
            }
        }
    }

    /**
     * 合约退回剩余红包（仅 creator 可调用 withdraw）
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun backRedPacketByContract(password: String, redPacket: RedPacketInfo) {
        launch {
            loading(false)
            try {
                withContext(Dispatchers.IO) {
                    val mnem = delegate.preference.getMnemonicString(password)
                        ?: throw Exception("助记词获取失败")
                    val credentials = deriveEthCredentials(mnem)
                    val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))
                    val packetIdBi = BigInteger(redPacket.packetId.trim())
                    val encoded = RedPacketEvmContract.encodeWithdraw(packetIdBi)
                    val receipt = sendSignedContractTransaction(
                        web3j,
                        credentials,
                        RedPacketConfig.CONTRACT_ADDRESS,
                        encoded,
                        BigInteger.ZERO,
                        receiptPollInitialMs = USER_RECEIPT_POLL_INITIAL_MS,
                        receiptPollMaxMs = USER_RECEIPT_POLL_MAX_MS
                    )
                    if (receipt.status != "0x1") {
                        throw Exception("合约退回失败")
                    }
                }
                dismiss()
                _backPacketResult.value = redPacket.packetId
            } catch (e: Exception) {
                dismiss()
                _receiveError.value = e
            }
        }
    }

    /**
     * 链上领取红包（claim），资金直接进入当前钱包 EVM 地址
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun receiveRedPacketByContract(password: String, packetId: String, message: ChatMessage) {
        launch {
            _receiveState.value = 1
            try {
                withContext(Dispatchers.IO) {
                    val mnem = delegate.preference.getMnemonicString(password)
                        ?: throw Exception("助记词获取失败")
                    val credentials = deriveEthCredentials(mnem)
                    val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))
                    val packetIdBi = BigInteger(packetId.trim())

                    val (rawInfo, claimedRaw) = coroutineScope {
                        val infoDeferred = async {
                            RedPacketEvmContract.ethCall(
                                web3j,
                                credentials.address,
                                RedPacketEvmContract.encodeGetRedPacket(packetIdBi)
                            )
                        }
                        val claimedDeferred = async {
                            RedPacketEvmContract.ethCall(
                                web3j,
                                credentials.address,
                                RedPacketEvmContract.encodeIsClaimed(packetIdBi, credentials.address)
                            )
                        }
                        Pair(infoDeferred.await(), claimedDeferred.await())
                    }
                    val packetInfo = RedPacketEvmContract.decodeGetRedPacket(rawInfo, packetId)
                        ?: throw RedPacketStateException(0, "红包不存在")

                    if (RedPacketEvmContract.decodeBoolResult(claimedRaw)) {
                        throw RedPacketStateException(5, "红包已领取")
                    }
                    if (packetInfo.isExpired) {
                        throw RedPacketStateException(4, "红包已过期")
                    }
                    when (packetInfo.status) {
                        2 -> throw RedPacketStateException(2, "红包已领完")
                        3 -> throw RedPacketStateException(3, "红包已退回")
                        4 -> throw RedPacketStateException(4, "红包已过期")
                    }

                    // 指定领取人红包（合约白名单）：非白名单地址直接提示，避免无效上链
                    if ((packetInfo.txIndex.toIntOrNull() ?: 0) > 0) {
                        val inWhitelistRaw = RedPacketEvmContract.ethCall(
                            web3j,
                            credentials.address,
                            RedPacketEvmContract.encodeIsInWhitelist(
                                packetIdBi, credentials.address
                            )
                        )
                        if (!RedPacketEvmContract.decodeBoolResult(inWhitelistRaw)) {
                            throw RedPacketStateException(0, "该红包仅限指定成员领取")
                        }
                    }

                    withContext(Dispatchers.Main) { _receiveState.value = 2 }

                    val encodedClaim = RedPacketEvmContract.encodeClaim(packetIdBi)
                    withContext(Dispatchers.Main) { _receiveState.value = 3 }
                    val claimSymbol = message.msg.symbol
                    val claimGasLimit = if (isPmmSymbol(claimSymbol)) {
                        PMM_CREATE_REDPACKET_GAS_LIMIT
                    } else {
                        BigInteger.valueOf(300_000L)
                    }
                    val receipt = sendSignedContractTransaction(
                        web3j,
                        credentials,
                        RedPacketConfig.CONTRACT_ADDRESS,
                        encodedClaim,
                        BigInteger.ZERO,
                        claimGasLimit,
                        null,
                        claimSymbol,
                        receiptPollInitialMs = USER_RECEIPT_POLL_INITIAL_MS,
                        receiptPollMaxMs = USER_RECEIPT_POLL_MAX_MS
                    )
                    if (receipt.status != "0x1") {
                        throw Exception("合约领取失败")
                    }

                    withContext(Dispatchers.Main) {
                        message.msg.state = 5
                        MessageSubscription.onMessage(Option.UPDATE_CONTENT, message)
                        database.messageDao().insert(message.toMessagePO())
                    }
                }
                _receiveState.value = 6
                _receiveResult.value = packetId
            } catch (e: Exception) {
                _receiveState.value = 6
                if (e is RedPacketStateException && e.state == 5) {
                    message.msg.state = 5
                    MessageSubscription.onMessage(Option.UPDATE_CONTENT, message)
                    database.messageDao().insert(message.toMessagePO())
                }
                _receiveError.value = e
            }
        }
    }

    /**
     * 通过EVM智能合约发送红包
     *
     * @param password     钱包密码，用于解密助记词并派生EVM私钥
     * @param amount       红包总金额（UI层面的数值，如 1.5）
     * @param packetCount  红包份数
     * @param packetType   红包类型（ChatConst: 0=单人, 1=拼手气, 2=固定金额）
     * @param tokenAddress ERC20代币合约地址，原生代币传空字符串
     * @param remark       红包备注
     * @param target       聊天对象
     * @param moduleAsset  选中的资产信息
     * @param ecPrivateKey 消息加密用ECC私钥
     * @param expireAt     过期时间戳（秒）
     * @param allowedAddresses 指定领取人白名单（0x 收款地址）；空表示所有人可领
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun sendRedPacketByContract(
        password: String,
        amount: Double,
        packetCount: Int,
        packetType: Int,
        tokenAddress: String,
        remark: String,
        target: ChatTarget,
        moduleAsset: ModuleAsset?,
        ecPrivateKey: String,
        expireAt: Long,
        allowedAddresses: List<String> = emptyList()
    ) {
        launch {
            _sendState.value = SEND_UI_PREPARE
            try {
                val packetIdStr = coroutineScope {
                    val contactDeferred = async(Dispatchers.IO) {
                        if (target.isGroup) {
                            manager.getGroupInfo(target.targetId)
                        } else {
                            manager.getUserInfo(target.targetId)
                        }
                    }
                    val packetIdStrInner = async(Dispatchers.IO) {
                        val mnem = delegate.preference.getMnemonicString(password)
                            ?: throw Exception("助记词获取失败")
                        val credentials = deriveEthCredentials(mnem)

                        val web3j = Web3j.build(HttpService(RedPacketConfig.RPC_URL))

                        val isNativeToken = tokenAddress.isBlank() ||
                            tokenAddress == ZERO_ADDRESS
                        val actualTokenAddr = if (isNativeToken) ZERO_ADDRESS else tokenAddress

                        val (decimals, prefetchedGasPrice) = resolveSendDecimalsAndPrefetchedGas(
                            web3j,
                            isNativeToken,
                            actualTokenAddr,
                            moduleAsset
                        )

                        // 合约 totalAmount 为最小单位；原生币时交易 value 必须与此一致（否则 amount=0 会 revert）
                        val totalAmount = BigDecimal(amount.toString())
                            .multiply(BigDecimal.TEN.pow(decimals))
                            .toBigInteger()

                        val isRandomPacket =
                            packetType == com.fzm.chat.core.data.ChatConst.RED_PACKET_LUCKY
                        if (!isRandomPacket && packetCount > 0) {
                            val rem = totalAmount.remainder(BigInteger.valueOf(packetCount.toLong()))
                            if (rem != BigInteger.ZERO) {
                                throw Exception("固定金额红包要求总金额能被份数整除")
                            }
                        }

                        withContext(Dispatchers.Main) { _sendState.value = SEND_UI_VALIDATE_AMOUNT }

                        // BTY 链上 btyerc 代币与 ETH ERC20 一致：先 approve 红包合约再 create
                        if (!isNativeToken && moduleAsset?.platform == "btyerc") {
                            withContext(Dispatchers.Main) { _sendState.value = SEND_UI_CHECK_ALLOWANCE }
                            ensureBtyercErc20Allowance(
                                web3j,
                                credentials,
                                actualTokenAddr,
                                credentials.address,
                                RedPacketConfig.CONTRACT_ADDRESS,
                                totalAmount,
                                moduleAsset
                            )
                        }

                        withContext(Dispatchers.Main) { _sendState.value = SEND_UI_CREATE_TX }

                        val functionName = if (isRandomPacket) {
                            "createRandomRedPacket"
                        } else {
                            "createFixedRedPacket"
                        }

                        // 指定领取人 = 合约白名单，只有这些地址能领取
                        val whitelist = allowedAddresses.distinct()
                        val function = Function(
                            functionName,
                            listOf(
                                Address(actualTokenAddr),
                                Uint256(totalAmount),
                                Uint256(BigInteger.valueOf(packetCount.toLong())),
                                Uint256(BigInteger.valueOf(expireAt)),
                                DynamicArray(Address::class.java, whitelist.map { Address(it) })
                            ),
                            listOf(object : TypeReference<Uint256>() {})
                        )
                        val encodedFunction = FunctionEncoder.encode(function)

                        val nonceDeferred = async(Dispatchers.IO) {
                            web3j.ethGetTransactionCount(
                                credentials.address, DefaultBlockParameterName.LATEST
                            ).send().transactionCount
                        }
                        val gasPriceDeferred = async(Dispatchers.IO) {
                            prefetchedGasPrice
                                ?: web3j.ethGasPrice().send().gasPrice
                        }
                        val nonce = nonceDeferred.await()
                        val gasPrice = if (shouldUsePmmGasPrice(moduleAsset, null)) {
                            PMM_GAS_PRICE_WEI
                        } else {
                            gasPriceDeferred.await()
                        }
                        val gasLimit = if (isPmmRedPacketAsset(moduleAsset)) {
                            PMM_CREATE_REDPACKET_GAS_LIMIT
                        } else {
                            // 白名单要写入合约存储，按人数追加 gas 上限（未用完不扣费）
                            BigInteger.valueOf(300_000L + 100_000L * whitelist.size)
                        }

                        val txValue = if (isNativeToken) totalAmount else BigInteger.ZERO
                        val rawTx = RawTransaction.createTransaction(
                            nonce, gasPrice, gasLimit,
                            RedPacketConfig.CONTRACT_ADDRESS,
                            txValue, encodedFunction
                        )
                        val signedMsg = TransactionEncoder.signMessage(
                            rawTx, RedPacketConfig.CHAIN_ID, credentials
                        )
                        val hexValue = Numeric.toHexString(signedMsg)

                        val txResponse = web3j.ethSendRawTransaction(hexValue).send()
                        if (txResponse.hasError()) {
                            throw Exception("交易发送失败: ${txResponse.error.message}")
                        }

                        withContext(Dispatchers.Main) { _sendState.value = SEND_UI_WAIT_RECEIPT }

                        val txHash = txResponse.transactionHash
                        val receipt = waitForTransactionReceipt(
                            web3j,
                            txHash,
                            USER_RECEIPT_POLL_INITIAL_MS,
                            USER_RECEIPT_POLL_MAX_MS
                        )
                        if (receipt.status != "0x1") {
                            throw Exception("合约交易执行失败")
                        }

                        withContext(Dispatchers.Main) { _sendState.value = SEND_UI_SEND_MSG }

                        parseRedPacketCreatedEvent(receipt)?.toString()
                            ?: throw Exception("无法解析红包ID")
                    }.await()

                    val contact = contactDeferred.await()
                    // 发送聊天红包消息
                    val servers = contact.getServerList()
                    if (servers.isNotEmpty()) {
                        val url = servers[0].address
                        val bizType =
                            if (packetType == com.fzm.chat.core.data.ChatConst.RED_PACKET_LUCKY) 0 else 1
                        val payload = MessageContent.redPacket(
                            packetIdStrInner,
                            moduleAsset?.chain ?: "",
                            if (moduleAsset?.isCoins == true) Msg.CoinType.Coins_VALUE else Msg.CoinType.Token_VALUE,
                            moduleAsset?.symbol ?: "",
                            remark,
                            bizType,
                            ecPrivateKey,
                            expireAt
                        )
                        val message = ChatMessage.create(
                            delegate.getAddress(),
                            target.targetId,
                            target.channelType,
                            Biz.MsgType.RedPacket,
                            payload
                        )
                        database.redPacketDao().insert(
                            RedPacketMessage(message.msgId, packetIdStrInner)
                        )
                        messageSender.send(url, message)
                    }
                    packetIdStrInner
                }
                _sendState.value = SEND_UI_DONE
                _sendResult.value = packetIdStr
            } catch (e: Exception) {
                e.printStackTrace()
                _sendState.value = SEND_UI_DONE
                _sendError.value = e
            }
        }
    }
    /**
     * 解析「指定领取人」的钱包收款地址（合约白名单 allowedAddresses）
     *
     * 地址取自对方链上资料同步的 0x 收款地址（EVM 地址）；缺失地址的成员单独返回，由界面提示。
     */
    suspend fun resolveReceiverWhitelist(receivers: List<String>): ReceiverResolveResult {
        if (receivers.isEmpty()) return ReceiverResolveResult(emptyList(), emptyList())
        val resolved = coroutineScope {
            receivers.distinct().map { address ->
                async(Dispatchers.IO) {
                    val contact = try {
                        manager.getUserInfo(address)
                    } catch (e: Exception) {
                        null
                    }
                    val evm = contact?.chains?.values.orEmpty()
                        .filter { it.startsWith("0x", ignoreCase = true) && it.length == 42 }
                        .distinct()
                    Pair(evm, contact?.getDisplayName() ?: address)
                }
            }.awaitAll()
        }
        return ReceiverResolveResult(
            resolved.flatMap { it.first }.distinct(),
            resolved.filter { it.first.isEmpty() }.map { it.second }
        )
    }

    fun getCredentialsFromMnemonic(mnemonic: String?): Credentials {
        // 生成种子
        val seed = MnemonicUtils.generateSeed(mnemonic, null)

        // 使用BIP44路径推导密钥：m/44'/60'/0'/0/0
        val path = intArrayOf(
            44 or Bip32ECKeyPair.HARDENED_BIT, 60 or Bip32ECKeyPair.HARDENED_BIT,
            0 or Bip32ECKeyPair.HARDENED_BIT, 0, 0
        )

        // 推导主密钥对
        val masterKeypair = Bip32ECKeyPair.generateKeyPair(seed)
        val derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, path)
        return Credentials.create(derivedKeyPair)
    }

    fun mnemToPrivateKey(mnemonic: String?): String {
        val credentials = getCredentialsFromMnemonic(mnemonic)
        val privateKey = Numeric.toHexStringNoPrefix(
            credentials.ecKeyPair.privateKey
        )
        return privateKey
    }

    private fun deriveEthCredentials(mnemonic: String): Credentials {
        val priKey = mnemToPrivateKey(mnemonic)
        return Credentials.create(priKey).also { credentials ->
            // 缓存红包使用的 EVM 地址：记录页不能弹密码、拿不到助记词时靠它匹配「我」的收发记录
            try {
                delegate.preference.RED_PACKET_EVM_ADDRESS = credentials.address
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 发红包用精度（与 [queryErc20DecimalsFromChain] 语义一致），并与 `ethGasPrice` 并行以降低 ERC20 路径延迟。
     * 原生币不发起 gas 预取（返回 second=null，由后续与 nonce 并行拉取）。
     */
    private suspend fun resolveSendDecimalsAndPrefetchedGas(
        web3j: Web3j,
        isNativeToken: Boolean,
        tokenContract: String,
        moduleAsset: ModuleAsset?
    ): Pair<Int, BigInteger?> {
        if (isNativeToken) {
            return Pair(RedPacketConfig.NATIVE_TOKEN_DECIMALS, null)
        }
        return coroutineScope {
            val decJob = async(Dispatchers.IO) {
                queryErc20DecimalsFromChain(web3j, tokenContract)
            }
            val gasJob = async(Dispatchers.IO) {
                web3j.ethGasPrice().send().gasPrice
            }
            val fromChain = decJob.await()
            val gas = gasJob.await()
            val decimals = fromChain ?: run {
                val fallback =
                    moduleAsset?.decimalPlaces?.takeIf { it in 1..36 } ?: 18
                logI("sendRedPacket: 链上 decimals 查询失败，使用回退精度 $fallback")
                fallback
            }
            Pair(decimals, gas)
        }
    }

    /** 与 [com.fzm.chat.redpacket.evm.RedPacketEvmContract] 一致，满足 Web3j `FunctionReturnDecoder` 的泛型签名。 */
    private fun erc20DecimalsOutputUint8(): List<TypeReference<out Type<*>>> = listOf(
        object : TypeReference<Uint8>() {}
    )

    private fun erc20DecimalsOutputUint256(): List<TypeReference<out Type<*>>> = listOf(
        object : TypeReference<Uint256>() {}
    )

    /**
     * ERC20 `decimals()`，兼容返回 uint8 / uint256 的合约实现。
     */
    private fun queryErc20DecimalsFromChain(web3j: Web3j, tokenContract: String): Int? {
        return try {
            val fn = Function(
                "decimals",
                emptyList(),
                erc20DecimalsOutputUint8()
            )
            val encoded = FunctionEncoder.encode(fn)
            val resp = web3j.ethCall(
                Transaction.createEthCallTransaction(
                    ZERO_ADDRESS,
                    tokenContract,
                    encoded
                ),
                DefaultBlockParameterName.LATEST
            ).send()
            if (resp.hasError()) return null
            val hex = resp.value ?: return null
            if (hex.isEmpty() || hex == "0x") return null
            decodeErc20DecimalsReturnData(hex)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeErc20DecimalsReturnData(hex: String): Int? {
        val fnUint8 = Function(
            "decimals",
            emptyList(),
            erc20DecimalsOutputUint8()
        )
        val fnUint256 = Function(
            "decimals",
            emptyList(),
            erc20DecimalsOutputUint256()
        )
        return try {
            val d = FunctionReturnDecoder.decode(hex, fnUint8.outputParameters)
            if (d.isEmpty()) return null
            (d[0] as Uint8).value.toInt().coerceIn(0, 255)
        } catch (_: Exception) {
            try {
                val d = FunctionReturnDecoder.decode(hex, fnUint256.outputParameters)
                if (d.isEmpty()) return null
                (d[0] as Uint256).value.toInt().coerceIn(0, 255)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * btyerc 代币：查询 allowance，不足则向代币合约发送 approve(红包合约, amount)。
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun ensureBtyercErc20Allowance(
        web3j: Web3j,
        credentials: Credentials,
        tokenContract: String,
        ownerAddress: String,
        spenderAddress: String,
        requiredAmount: BigInteger,
        moduleAsset: ModuleAsset?
    ) {
        val allowanceFn = Function(
            "allowance",
            listOf(Address(ownerAddress), Address(spenderAddress)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val encodedAllowance = FunctionEncoder.encode(allowanceFn)
        val allowanceResp = web3j.ethCall(
            Transaction.createEthCallTransaction(
                ownerAddress,
                tokenContract,
                encodedAllowance
            ),
            DefaultBlockParameterName.LATEST
        ).send()
        if (allowanceResp.hasError()) {
            throw Exception("查询代币授权失败: ${allowanceResp.error?.message}")
        }
        val rawAllowance = allowanceResp.value
        val currentAllowance = if (rawAllowance.isNullOrEmpty() || rawAllowance == "0x") {
            BigInteger.ZERO
        } else {
            val decoded = FunctionReturnDecoder.decode(rawAllowance, allowanceFn.outputParameters)
            (decoded[0] as Uint256).value
        }
        if (currentAllowance >= requiredAmount) return

        withContext(Dispatchers.Main) { _sendState.value = SEND_UI_APPROVE_WAIT }

        val approveFn = Function(
            "approve",
            listOf(Address(spenderAddress), Uint256(requiredAmount)),
            emptyList()
        )
        val encodedApprove = FunctionEncoder.encode(approveFn)
        val approveGasLimit = if (isPmmRedPacketAsset(moduleAsset)) {
            PMM_APPROVE_GAS_LIMIT
        } else {
            BigInteger.valueOf(100_000L)
        }
        val receipt = sendSignedContractTransaction(
            web3j,
            credentials,
            tokenContract,
            encodedApprove,
            BigInteger.ZERO,
            approveGasLimit,
            moduleAsset,
            null
        )
        if (receipt.status != "0x1") {
            throw Exception("代币授权失败")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun waitForTransactionReceipt(
        web3j: Web3j,
        txHash: String,
        initialPollMs: Long = RECEIPT_POLL_INITIAL_MS,
        maxPollMs: Long = RECEIPT_POLL_MAX_MS,
    ): org.web3j.protocol.core.methods.response.TransactionReceipt {
        var nextDelayMs = initialPollMs
        repeat(RECEIPT_POLL_MAX_ATTEMPTS) {
            val receiptResponse = web3j.ethGetTransactionReceipt(txHash).send()
            if (receiptResponse.transactionReceipt.isPresent) {
                return receiptResponse.transactionReceipt.get()
            }
            delay(nextDelayMs)
            nextDelayMs = (nextDelayMs * 3 / 2).coerceAtMost(maxPollMs)
        }
        throw Exception("等待交易确认超时")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun sendSignedContractTransaction(
        web3j: Web3j,
        credentials: Credentials,
        toAddress: String,
        encodedFunction: String,
        txValue: BigInteger,
        gasLimit: BigInteger = BigInteger.valueOf(300_000L),
        moduleAsset: ModuleAsset? = null,
        /** 领取等场景无 [moduleAsset] 时，用消息里的币种符号判断是否走 PMM 矿工费 */
        pmmHintSymbol: String? = null,
        receiptPollInitialMs: Long = RECEIPT_POLL_INITIAL_MS,
        receiptPollMaxMs: Long = RECEIPT_POLL_MAX_MS,
    ): org.web3j.protocol.core.methods.response.TransactionReceipt = coroutineScope {
        val nonceDeferred = async(Dispatchers.IO) {
            web3j.ethGetTransactionCount(
                credentials.address, DefaultBlockParameterName.LATEST
            ).send().transactionCount
        }
        val gasPriceDeferred = async(Dispatchers.IO) {
            web3j.ethGasPrice().send().gasPrice
        }
        val nonce = nonceDeferred.await()
        val gasPrice = if (shouldUsePmmGasPrice(moduleAsset, pmmHintSymbol)) {
            PMM_GAS_PRICE_WEI
        } else {
            gasPriceDeferred.await()
        }
        val rawTx = RawTransaction.createTransaction(
            nonce, gasPrice, gasLimit,
            toAddress,
            txValue, encodedFunction
        )
        val signedMsg = TransactionEncoder.signMessage(
            rawTx, RedPacketConfig.CHAIN_ID, credentials
        )
        val hexValue = Numeric.toHexString(signedMsg)
        val txResponse = web3j.ethSendRawTransaction(hexValue).send()
        if (txResponse.hasError()) {
            throw Exception("交易发送失败: ${txResponse.error.message}")
        }
        val txHash = txResponse.transactionHash
            ?: throw Exception("交易哈希为空")
        waitForTransactionReceipt(web3j, txHash, receiptPollInitialMs, receiptPollMaxMs)
    }

    private fun parseRedPacketCreatedEvent(
        receipt: org.web3j.protocol.core.methods.response.TransactionReceipt
    ): BigInteger? {
        // contract-api22.md：id,creator 为 indexed；中间含 tokenSymbolKey(bytes32 indexed)。旧版无 bytes32。
        val newSig = "RedPacketCreated(uint256,address,address,bytes32,uint8,uint256,uint256,uint256,uint256)"
        val legacySig = "RedPacketCreated(uint256,address,address,uint8,uint256,uint256,uint256,uint256)"
        val newHash = EventEncoder.buildEventSignature(newSig)
        val legacyHash = EventEncoder.buildEventSignature(legacySig)
        for (log in receipt.logs) {
            val topics = log.topics ?: continue
            if (topics.isEmpty()) continue
            if (topics[0] != newHash && topics[0] != legacyHash) continue
            if (topics.size > 1) {
                return Numeric.toBigInt(topics[1])
            }
        }
        return null
    }

    fun fetchGroupUsers(gid: Long) = liveData(Dispatchers.Main) {
        emitSource(database.groupUserDao().getGroupUserList(gid))
        groupRepo.getGroupUserList(null, gid)
    }

    fun getBrowserUrl(platform: String) {
        launch {
            _browserUrl.value = BWallet.get().getBrowserUrl(platform)
        }
    }
}