package com.fzm.chat.wallet.ui

import androidx.lifecycle.*
import com.fzm.chat.biz.base.AppConst
import com.fzm.chat.biz.db.AppDatabase
import com.fzm.chat.biz.db.po.LocalNote
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.Contact
import com.fzm.chat.core.data.bean.Option
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.toMessagePO
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.core.logic.MessageSender
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.main.SimpleTx
import com.fzm.chat.router.redpacket.ModuleAsset
import com.fzm.chat.router.redpacket.RedPacketModule
import com.fzm.chat.router.redpacket.RedPacketService
import com.fzm.chat.router.route
import com.fzm.chat.wallet.WalletSDK
import com.fzm.chat.wallet.data.WalletRepository
import com.fzm.chat.wallet.data.bean.TransactionInfo
import com.fzm.chat.wallet.data.bean.WalletAsset
import com.fzm.chat.wallet.data.coinType
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.bean.Miner
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.utils.decimalPlaces
import com.fzm.wallet.sdk.utils.fullChain
import com.fzm.wallet.sdk.utils.isCoin
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import dtalk.biz.Biz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.litepal.LitePal

/**
 * @author zhengjy
 * @since 2021/08/03
 * Description:
 */
class WalletViewModel(
    private val contactManager: ContactManager,
    private val repository: WalletRepository,
    private val delegate: LoginDelegate,
    private val messageSender: MessageSender,
    private val database: AppDatabase,
) : LoadingViewModel(), LoginDelegate by delegate {

    private var coinFilter: ((Coin) -> Boolean)? = null

    private val chatDatabase: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    private val _transferResult = MutableLiveData<String>()
    val transferResult: LiveData<String>
        get() = _transferResult

    /**
     * 资产
     */
    private val _moduleAssets = MutableLiveData<List<Coin>>()
    val moduleAssets: LiveData<List<Coin>> by lazy {
        launch {
            WalletSDK().getCoinBalances(MyWallet.getID().toString())
                .collect { _moduleAssets.value = it }
        }
        // 监听全局快照刷新请求（添加资产页返回等场景），立即从数据库重查列表
        launch {
            while (true) {
                WalletSDK.assetSnapshotRefreshRequests.receive()
                refreshAssetsSnapshot()
            }
        }
        _moduleAssets
    }

    /**
     * 立即刷新资产列表（如添加资产页 +/- 币种后返回时调用）：
     * 直接从数据库快照加载当前启用币种，不等 Flow 轮询。
     */
    fun refreshAssetsSnapshot() {
        launch {
            val walletId = MyWallet.getID().toString()
            val coins = withContext(Dispatchers.IO) {
                runCatching {
                    LitePal.where(
                        "pwallet_id = ? and status = ?",
                        walletId,
                        Coin.STATUS_ENABLE.toString()
                    ).find(Coin::class.java, true)
                }.getOrDefault(emptyList())
            }
            _moduleAssets.value = coins
        }
    }


    /**
     * nft资产
     */
    private val _nftAssets = MutableLiveData<List<Coin>>()
    val nftAssets: LiveData<List<Coin>>
        get() = _nftAssets

    private val _asset = MutableLiveData<WalletAsset>()
    val asset: LiveData<WalletAsset>
        get() = _asset

    private val _txList = MutableLiveData<TransactionInfo.Wrapper>()
    val txList: LiveData<TransactionInfo.Wrapper>
        get() = _txList

    private val _txOutList = MutableLiveData<TransactionInfo.Wrapper>()
    val txOutList: LiveData<TransactionInfo.Wrapper>
        get() = _txOutList

    private val _txInList = MutableLiveData<TransactionInfo.Wrapper>()
    val txInList: LiveData<TransactionInfo.Wrapper>
        get() = _txInList

    private val _txInfo = MutableLiveData<TransactionInfo>()
    val txInfo: LiveData<TransactionInfo>
        get() = _txInfo

    private val _accountAsset = MutableLiveData<List<ModuleAsset>>()
    val accountAsset: LiveData<List<ModuleAsset>>
        get() = _accountAsset

    private val _miner = MutableLiveData<Miner?>()
    val miner: LiveData<Miner?>
        get() = _miner

    private val _getContact = MutableLiveData<Contact?>()
    val getContact: LiveData<Contact?>
        get() = _getContact

    private val _enoughChainAssets = MutableLiveData(true)
    val enoughChainAssets: LiveData<Boolean>
        get() = _enoughChainAssets

    private val redPacketService by route<RedPacketService>(RedPacketModule.SERVICE)

    fun setCoinFilter(coinFilter: (Coin) -> Boolean) {
        this.coinFilter = coinFilter
    }

    private var feeJob: Job? = null

    fun getRecommendedFee(chain: String, name: String) {
        feeJob?.cancel()
        feeJob = launch {
            val fee = BWallet.get().getRecommendedFee(chain)
            if (isActive) {
                if (fee != null) {
                    _miner.value = fee
                } else {
                    _miner.value = null
                }
            }
        }
    }

    /**
     * 向target转账
     */
    fun transfer(
        coin: Coin,
        target: String,
        coinAddress: String,
        symbol: String,
        amount: Double,
        fee: Double,
        password: String,
        note: String? = null,
        localNote: String? = null,
        sendMsg: Boolean = true
    ) {
        request<String> {
            onRequest {
                val result = repository.transfer(coinAddress, coin, amount, fee, password, note)
                if (result.isSucceed()) {
                    val hash = result.data()
                    if (sendMsg) {
                        val contact = contactManager.getUserInfo(target)
                        val url = contact.getServerList().firstOrNull()?.address ?: ""
                        val tokensymbol = if (coin.platform == "btyerc") {
                            coin.contract_address
                        } else {
                            symbol
                        }

                        val payload = MessageContent.transfer(
                            coin.chain,
                            coin.platform,
                            hash,
                            tokensymbol,
                            coin.coinType
                        )
                        val message = ChatMessage.create(
                            delegate.getAddress(),
                            target,
                            ChatConst.PRIVATE_CHANNEL,
                            Biz.MsgType.Transfer,
                            payload
                        )
                        //queryTransaction(delegate.getAddress() ?: "", coinAddress, message, coin)
                        payload.txAmount = amount.toString()
                        payload.txStatus = SimpleTx.SUCCESS
                        payload.txInvalid = false
                        MessageSubscription.onMessage(Option.UPDATE_CONTENT, message)
                        chatDatabase.messageDao().insert(message.toMessagePO())

                        messageSender.send(url, message)
                        return@onRequest Result.Success(hash)
                    } else {
                        return@onRequest Result.Success(hash)
                    }
                } else {
                    return@onRequest Result.Error(result.error())
                }
            }
            onSuccess { hash ->
                localNote?.also { note ->
                    launch {
                        database.localTxNoteDao().insert(LocalNote(hash, note))
                    }
                }
                _transferResult.value = hash
            }
        }
    }


    private var checkChainAssetJob: Job? = null

    fun checkChainAssetEnough(chain: String, fee: Double) {
        checkChainAssetJob?.cancel()
        checkChainAssetJob = launch {
//            val balance = BWallet.get().getMainCoin(chain)?.balance ?: "0"
//            _enoughChainAssets.value = balance.toDouble() > fee

            // 2022年3月10日12点03分：token使用代扣，所以不检查主链资产
            _enoughChainAssets.value = true
        }
    }

    fun hasPassword() = delegate.preference.hasChatPassword()

    fun getLocalNoteByHash(hash: String) = database.localTxNoteDao().getLocalTxNote(hash)

    fun saveLocalNote(hash: String, note: String) {
        launch { database.localTxNoteDao().insert(LocalNote(hash, note)) }
    }

    fun observeTxList(
        type: Int,
        lifecycleOwner: LifecycleOwner,
        observer: Observer<TransactionInfo.Wrapper>
    ) {
        when (type) {
            0 -> txList
            1 -> txOutList
            2 -> txInList
            else -> throw Exception("交易列表type类型错误")
        }.observe(lifecycleOwner, observer)
    }


    fun getTargetInfo(targetId: String?) {
        launch {
            _getContact.value = targetId?.let { contactManager.getUserInfo(it) }
        }
        //if (targetId != null) emit(contactManager.getUserInfo(targetId))
    }

    fun getNFTAssetList(showLoading: Boolean = true) {
        request<List<Coin>>(showLoading) {
            onRequest {
                // TODO : 改成获取NFT资产
                Result.Error(ApiException("正在开发中，暂不支持NFT"))
            }
            onSuccess {
                _nftAssets.value = it
            }
        }
    }

    fun getCoinBalance(coin: Coin) {
        request<Coin> {
            onRequest {
                _asset.value = WalletAsset(
                    coin.name,
                    coin.address,
                    coin.balance,
                    null,
                    "${coin.platform}.${coin.name}"
                )
                Result.Success(BWallet.get().getCoinBalance(coin, true))
            }
            onSuccess {
                _asset.value = WalletAsset(
                    coin.name,
                    coin.address,
                    coin.balance,
                    null,
                    "${coin.platform}.${coin.name}"
                )
            }
        }
    }

    fun getTransactionList(coin: Coin, type: Int, index: Long, refreshAsset: Boolean = true) {
        viewModelScope.launch {
            try {
                val coinToken = coin.newChain

                //val chain = if (coin.platform == "btyerc") "BTY" else coin.chain
                val list = repository.getTransactionList(
                    coinToken.cointype, "", coin.address, coin.contract_address, index,
                    AppConst.PAGE_SIZE.toLong(), 0, type
                )
                val wrapper = TransactionInfo.Wrapper(index == 0L, list.data())
                when (type) {
                    0 -> _txList.value = wrapper
                    1 -> _txOutList.value = wrapper
                    2 -> _txInList.value = wrapper
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }


        }
    }

    fun getTransactionById(
        cointype: String,
        tokensymbol: String,
        contractAddress: String,
        txid: String
    ) {
        request<TransactionInfo?> {
            onRequest {
                repository.getTransactionById(cointype, tokensymbol, contractAddress, txid)
            }
            onSuccess {
                _txInfo.value = it
            }
        }
    }

    fun getAccountAssetByAddress() {
        launch {
            _accountAsset.value = getAddress()?.let { address ->
                BWallet.get().getRedPacketAssets(address)
                    .filter { it.platform == redPacketService?.getPlatform() }
                    .map {
                        ModuleAsset(
                            it.nickname,
                            it.fullChain,
                            it.isCoin,
                            it.name ?: "",
                            it.balance ?: "",
                            it.decimalPlaces,
                            it.contract_address ?: "",
                            it.platform ?: ""
                        )
                    }
            } ?: emptyList()
        }
    }

    suspend fun withDrawAssets(assets: List<ModuleAsset>) {
        loading(true)
        for (item in assets) {
            if (item.balance != "0") {
                redPacketService?.withDrawRedPacket(item)
            }
        }
        dismiss()
    }


}