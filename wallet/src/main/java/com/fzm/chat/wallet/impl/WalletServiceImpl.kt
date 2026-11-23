package com.fzm.chat.wallet.impl

import android.content.Context
import android.util.Log
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.repo.ContractRepository
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.utils.CipherUtils
import com.fzm.chat.router.main.SimpleTx
import com.fzm.chat.router.redpacket.ModuleAsset
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.fzm.chat.wallet.WalletConfig
import com.fzm.chat.wallet.data.WalletRepository
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.base.REGEX_CHINESE
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.wallet.sdk.exception.ImportWalletException
import com.fzm.wallet.sdk.utils.GoWallet
import com.fzm.wallet.sdk.utils.MMkvUtil
import com.fzm.wallet.sdk.utils.decimalPlaces
import com.fzm.wallet.sdk.utils.fullChain
import com.fzm.wallet.sdk.utils.isCoin
import com.zjy.architecture.di.rootScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.litepal.LitePal
import org.litepal.extension.find
import walletapi.Walletapi

/**
 * @author zhengjy
 * @since 2021/08/13
 * Description:
 */
@Route(path = WalletModule.SERVICE)
class WalletServiceImpl : WalletService {

    private val repository by rootScope.inject<WalletRepository>()
    private val delegate by rootScope.inject<LoginDelegate>()
    private val contractRepository by rootScope.inject<ContractRepository>()

    override suspend fun importMnem(
        user: String,
        mnem: String,
        name: String,
        password: String,
        phone: String,
        email: String
    ) {
        val wallet = BWallet.get().getAllWallet(user).firstOrNull()
        if (wallet == null) {
            val coins = when (W.appType) {
                WConfig.APP_CHAT33PRO -> chat33proCoins
                WConfig.APP_CIP -> cipCoins
                else -> chat33proCoins
            }
            val config = WalletC.mnemonicWallet(
                mnem,
                name,
                password.ifEmpty { CipherUtils.DEFAULT_PASSWORD },
                user,
                coins,
                phone,
                email
            )
            importWallet(config)
        } else {
            BWallet.get().changeWallet(wallet)
        }
        // 钱包导入/切换完成后本地币种已就绪：用户信息也就绪时立即补一次收款地址同步，
        // 不必等主界面 onResume 的 5 秒轮询（登录流程也会再兜底一次）
        if (delegate.current.value != null) {
            runCatching { updateChainAddress(null) }
                .onSuccess { Log.d("ChainSync", "importMnem 后主动同步收款地址：$it") }
                .onFailure {
                    Log.e("ChainSync", "importMnem 后主动同步收款地址异常：${it.message}", it)
                }
        } else {
            Log.d("ChainSync", "importMnem 完成，用户信息未就绪，交由登录流程/主界面同步")
        }
    }

    override suspend fun closeWallet() {
        BWallet.get().close()
    }

    override fun getDefaultFee(): Double {
        return WalletConfig.DEFAULT_FEE
    }

    override suspend fun getTransactionByHash(
        chain: String,
        platform: String,
        hash: String,
        symbol: String
    ): Result<SimpleTx> {
        return try {
            val contractAddress = symbol
            val treaty = if (contractAddress.isEmpty()) "2" else "1"
            val coinToken = GoWallet.newCoinType(chain, symbol, platform, treaty, contractAddress)
            val transactions = repository.getTransactionById(
                coinToken.cointype,
                coinToken.tokenSymbol,
                contractAddress,
                hash
            )
            Result.success(transactions.data().let {
                SimpleTx(
                    hash,
                    it?.blockTime ?: 0,
                    it?.fee ?: "0",
                    it?.from ?: "",
                    it?.to ?: "",
                    it?.height ?: 0,
                    it?.status.toString(),
                    it?.value ?: "0",
                    it?.note
                )
            })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getModuleAssets(address: String): List<ModuleAsset> {
        val coins = repository.getAssetList(address).dataOrNull()
            ?.map {
                Log.v("wallet", "it == $it")
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

        Log.v("wallet", "coins == $coins")

        return coins ?: emptyList()
    }

    override suspend fun updateChainAddress(address: Map<String, String>?): String {
        var coinsError: String? = null
        val coins = if (address.isNullOrEmpty()) {
            runCatching { BWallet.get().getAllCoins() }.getOrElse {
                coinsError = "${it.javaClass.simpleName}: ${it.message}"
                Log.e("ChainSync", "读取本地钱包币种失败：$coinsError", it)
                emptyList<Coin>()
            }
        } else {
            emptyList()
        }
        val map = if (address.isNullOrEmpty()) {
            coins.associate { Pair(it.chain, it.address) }
        } else {
            address
        }
        Log.d("ChainSync", "上传收款地址，本地待传 map = $map")
        if (map.isEmpty()) {
            // 钱包未就绪（本地暂无币种）时不要误报成功，返回明确状态供上层稍后重试
            // 附带诊断信息：是「币种为空」还是「SDK 异常 / 当前钱包未设置」
            val walletId = runCatching { MyWallet.getID() }.getOrNull()
            val currentWallet = runCatching { BWallet.get().getCurrentWallet()?.id }.getOrNull()
            val allCoins = runCatching { BWallet.get().getAllCoins()?.size }.getOrNull()
            Log.w(
                "ChainSync",
                "本地钱包暂无待同步的链地址：coins=${coins.size}, allCoins=$allCoins, " +
                    "walletId=$walletId, currentWallet=$currentWallet, " +
                    "error=$coinsError, loggedIn=${delegate.getAddress() != null}"
            )
            return "local_empty"
        }
        val result = repository.setChainAddress(map)
        // 成功统一返回 "ok"（避免交易 data 为空被误判为失败）；失败返回具体原因便于提示
        return when {
            result.isSucceed() -> {
                Log.d("ChainSync", "上传收款地址成功")
                "ok"
            }
            else -> {
                Log.e("ChainSync", "上传收款地址失败：${result.error()?.message ?: result}")
                Log.e("ChainSync", "同步差异诊断：${runCatching { queryChainAddressSyncState() }.getOrNull()}")
                result.error()?.message ?: "fail"
            }
        }
    }

    /**
     * 查询收款地址同步状态：对比本机钱包各链地址与链上资料已登记的 chain.* 字段
     */
    override suspend fun queryChainAddressSyncState(): String {
        val address = delegate.getAddress() ?: return "未登录"
        val local = BWallet.get().getAllCoins()
            .groupBy { it.chain }
            .mapValues { (_, list) -> list.firstOrNull()?.address ?: "" }
            .filterValues { it.isNotEmpty() }
        val onChain = runCatching {
            contractRepository.getFriendUser(address).dataOrNull()?.chainAddress ?: emptyMap()
        }.getOrElse {
            Log.e("ChainSync", "查询链上资料失败：${it.message}")
            emptyMap()
        }
        return buildString {
            append("本机地址: $local\n")
            append("链上已登记: $onChain\n")
            val miss = local.keys.filter { onChain[it].isNullOrEmpty() }
            val diff = local.filter { (k, v) -> !onChain[k].isNullOrEmpty() && onChain[k] != v }.keys
            when {
                miss.isNotEmpty() && diff.isNotEmpty() ->
                    append("链上缺失(未同步): $miss；地址不一致: $diff")
                miss.isNotEmpty() -> append("链上缺失(未同步): $miss")
                diff.isNotEmpty() -> append("地址不一致: $diff")
                local.isEmpty() -> append("本机暂无币种，无需同步")
                else -> append("本机与链上完全一致，已同步成功")
            }
        }
    }

    override fun init(context: Context?) {

    }
}

private val chat33proCoins = listOf(
    Coin().apply {
        chain = "ETH"
        name = "ETH"
        netId = "90"
        platform = "ethereum"
        treaty = "1"
    },
    Coin().apply {
        chain = "BNB"
        name = "BNB"
        netId = "641"
        platform = "bnb"
        treaty = "1"
    },
    Coin().apply {
        chain = "ETH"
        name = "BTY"
        netId = "732"
        platform = "ethereum"
        treaty = "1"
    },
    Coin().apply {
        chain = "BTY"
        name = "BTY"
        netId = "154"
        platform = "bty"
        treaty = "1"
    },

    )
private val cipCoins = listOf(
    Coin().apply {
        chain = "BNB"
        name = "BNB"
        netId = "641"
        platform = "bnb"
        treaty = "1"
    },
    Coin().apply {
        chain = "POL"
        name = "POL"
        netId = "739"
        platform = "pol"
        treaty = "2"
    },

    )


//新增
suspend fun importWallet(configuration: WalletC) {
    return with(configuration) {
        if (mnemonic.isNullOrEmpty()) {
            throw ImportWalletException("助记词不能为空")
        }
        if (walletName.isNullOrEmpty()) {
            throw ImportWalletException("钱包名称不能为空")
        }
        if (password.isNullOrEmpty()) {
            throw ImportWalletException("钱包密码不能为空")
        }
        val type = if (mnemonic!!.substring(0, 1).matches(REGEX_CHINESE.toRegex())) {
            PWallet.TYPE_CHINESE
        } else PWallet.TYPE_ENGLISH
        val mnem = if (type == PWallet.TYPE_CHINESE) getChineseMnem(mnemonic!!) else mnemonic!!

        val hdWallet = withContext(Dispatchers.IO) {
            GoWallet.getHDWallet(Walletapi.TypeBtyString, mnem)
        } ?: throw ImportWalletException("助记词不存在")

        val pubKey = GoWallet.encodeToStrings(hdWallet.newKeyPub(0))
        val count = LitePal.where("pubkey = ?", pubKey).find<Coin>(true)

        if (count.isNotEmpty()) {
            if (count[0].getpWallet().type == PWallet.TYPE_NOMAL) {
                throw ImportWalletException("助记词重复")
            }
        }
        val wallet = PWallet()

        wallet.also {
            it.mnemType = type
            it.mnem = mnem
            it.type = PWallet.TYPE_NOMAL
            it.name = walletName
            it.password = password
            it.user = configuration.user
            it.phone = configuration.phone
            it.email = configuration.email
        }
        val coinList = configuration.coins

        withContext(Dispatchers.IO) {
            coinList.forEachIndexed { index, coin ->
                val goChain =
                    if (coin.chain == "BNB" || coin.chain == "CCC" || coin.chain == GoWallet.PARA || coin.chain == "POL") "ETH" else coin.chain
                val hdWallet = GoWallet.getHDWallet(goChain, wallet.mnem)
                val pubkey = hdWallet!!.newKeyPub(0)
                val address = hdWallet.newAddress_v2(0)
                val pubkeyStr = GoWallet.encodeToStrings(pubkey)
                coin.sort = index
                coin.status = Coin.STATUS_ENABLE
                coin.pubkey = pubkeyStr
                coin.address = address
            }
            for (coinItem in coinList) {
                coinItem.save()
            }
            wallet.coinList.addAll(coinList)
            val bpassword = GoWallet.encPasswd(wallet.password)
            wallet.mnem = GoWallet.encMenm(bpassword!!, wallet.mnem)
            wallet.password = GoWallet.passwdHash(bpassword)
            wallet.save()
            val id = wallet.id
            MMkvUtil.encode("CURRENT_USER", configuration.user)
            MMkvUtil.encode("${configuration.user}${PWallet.PWALLET_ID}", id)
            if (id != MyWallet.ID_DEFAULT) {
                MyWallet.setId(id)
            }
        }
    }
}

fun getChineseMnem(mnem: String): String {
    val afterString = mnem.replace(" ", "")
    val afterString2 = afterString.replace("\n", "")
    return afterString2.replace("", " ").trim()
}
