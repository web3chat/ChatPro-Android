package com.fzm.chat.wallet.ui.coins

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fzm.chat.biz.base.AppConst
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.MnemonicManager
import com.fzm.wallet.sdk.db.entity.AddCoinTabBean
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.wallet.sdk.utils.GoWallet
import com.zjy.architecture.data.Result
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.litepal.LitePal
import org.litepal.extension.find

/**
 * @author zhengjy
 * @since 2022/02/28
 * Description:
 */
class CoinViewModel(private val delegate: LoginDelegate) : LoadingViewModel() {

    private val _chainAssets by lazy { MutableLiveData<List<AddCoinTabBean>>() }
    val chainAssets: LiveData<List<AddCoinTabBean>>
        get() = _chainAssets

    private val _searchResult by lazy { MutableLiveData<List<Coin>>() }
    val searchResult: LiveData<List<Coin>>
        get() = _searchResult

    private val _homeCoins by lazy { MutableLiveData<List<Coin>>() }
    val homeCoins: LiveData<List<Coin>>
        get() = _homeCoins

    val netCoinsMap = HashMap<String, Coin>()

    /**
     * 刷新本地币种（首页币种 = 资产页可管理的全部币）。
     * 列出当前钱包全部记录（含已启用与已隐藏的），
     * 已启用显示"-"可隐藏，未启用显示"+"可显示。
     */
    fun refreshHomeCoins() {
        viewModelScope.launch {
            val coins = withContext(Dispatchers.IO) {
                val walletId = MyWallet.getID()
                runCatching {
                    LitePal.where("pwallet_id = ?", walletId.toString())
                        .find(Coin::class.java, true)
                }.getOrDefault(emptyList())
            }
            Log.d(
                "CoinManage",
                "refreshHomeCoins count=${coins.size} -> " +
                    coins.joinToString { "${it.name}:${it.status}" }
            )
            netCoinsMap.clear()
            netCoinsMap.putAll(coins.filter { it.netId != null }.associateBy { it.netId })
            _homeCoins.value = coins
        }
    }

    /**
     * 切换币种在首页(资产页)的显示状态：
     * enable=true 显示(启用)；enable=false 隐藏(仅置为非启用，保留记录可恢复)。
     */
    suspend fun toggleHomeCoin(coin: Coin, enable: Boolean): Boolean {
        val ok = try {
            withContext(Dispatchers.IO) {
                coin.status = if (enable) Coin.STATUS_ENABLE else 0
                coin.save()
            }
            true
        } catch (e: Exception) {
            Log.e("CoinManage", "toggleHomeCoin error", e)
            false
        }
        if (ok) {
            _coinChanged.postValue(coin)
        }
        refreshHomeCoins()
        return ok
    }

    fun hasPassword() = delegate.preference.hasChatPassword()

    fun searchCoins(page: Int, keywords: String, chain: String, platform: String) {
        request<List<Coin>> {
            onRequest {
                try {
                    val coins = BWallet.get()
                        .searchCoins(page, AppConst.PAGE_SIZE, keywords, chain, platform)
                    Result.Success(coins)
                } catch (e: Exception) {
                    Result.Error(e)
                }
            }
            onSuccess {
                _searchResult.value = it
            }
        }
    }

    private val _operationError = MutableLiveData<String?>()
    val operationError: LiveData<String?>
        get() = _operationError

    /** 币种状态变化事件(添加/隐藏/显示成功后触发，携带最新状态的 Coin) */
    private val _coinChanged = MutableLiveData<Coin?>()
    val coinChanged: LiveData<Coin?>
        get() = _coinChanged

    suspend fun addCoin(coin: Coin, index: Int, password: String): Boolean {
        Log.d(
            "CoinManage",
            "addCoin index=$index pwdLen=${password.length} coin=[id=${coin.id} name=${coin.name} " +
                "chain=${coin.chain} platform=${coin.platform} contract=${coin.contract_address} " +
                "netId=${coin.netId} address=${coin.address} status=${coin.status} sort=${coin.sort}]"
        )
        val ok = try {
            withContext(Dispatchers.IO) {
                persistCoin(coin, password)
            }
            Log.d("CoinManage", "addCoin -> persist ok")
            true
        } catch (e: Exception) {
            Log.e("CoinManage", "addCoin -> persist FAILED", e)
            _operationError.postValue(e.message ?: "添加失败")
            false
        }
        if (ok) {
            _operationError.postValue(null)
            coin.status = Coin.STATUS_ENABLE
            _coinChanged.postValue(coin)
        }
        refreshHomeCoins()
        return ok
    }

    /**
     * 本地派生地址并落库启用该币（绕过 SDK addCoins 静默不生效的问题）。
     * 若该币已存在于钱包(可能为停用态)，直接重新启用。
     */
    private suspend fun persistCoin(coin: Coin, password: String) {
        val walletId = MyWallet.getID()
        val pwallet = LitePal.find<PWallet>(walletId, true)
            ?: throw Exception("当前钱包不存在")

        val existing = pwallet.coinList.firstOrNull {
            it.name == coin.name && it.chain == coin.chain
        }
        if (existing != null) {
            Log.d("CoinManage", "persistCoin: 已存在币 ${existing.name}, 重新启用")
            existing.status = Coin.STATUS_ENABLE
            existing.save()
            return
        }

        // 用钱包助记词为该币派生链地址（与转账/导入钱包同源）
        val result = MnemonicManager.checkPassword(password)
        if (!result) {
            throw Exception("密码错误")
        }
        val mnem = MnemonicManager.getMnemonicWords(password)
            ?: throw Exception("密码错误，无法解锁钱包")
        val goChain = when (coin.chain) {
            "BNB", "CCC", GoWallet.PARA, "POL" -> "ETH"
            else -> coin.chain
        }
        val hdWallet = GoWallet.getHDWallet(goChain, mnem)
            ?: throw Exception("创建钱包地址失败")
        val pubkey = hdWallet.newKeyPub(0)
        coin.address = hdWallet.newAddress_v2(0)
        coin.pubkey = GoWallet.encodeToStrings(pubkey)
        if (coin.platform.isNullOrEmpty()) {
            coin.platform = coin.chain?.lowercase()
        }
        coin.status = Coin.STATUS_ENABLE
        coin.sort = (pwallet.coinList.maxOfOrNull { it.sort } ?: 0) + 1
        coin.setpWallet(pwallet)
        coin.save()
        Log.d(
            "CoinManage",
            "persistCoin saved name=${coin.name} chain=${coin.chain} address=${coin.address} status=${coin.status}"
        )
    }

    suspend fun removeCoin(coin: Coin, index: Int): Boolean {
        Log.d(
            "CoinManage",
            "removeCoin(hide) index=$index coin=[id=${coin.id} name=${coin.name} status=${coin.status}]"
        )
        val ok = try {
            withContext(Dispatchers.IO) {
                coin.status = 0
                coin.save()
            }
            Log.d("CoinManage", "removeCoin(hide) ok")
            true
        } catch (e: Exception) {
            Log.e("CoinManage", "removeCoin(hide) FAILED", e)
            _operationError.postValue("隐藏失败")
            false
        }
        if (ok) {
            _operationError.postValue(null)
            _coinChanged.postValue(coin)
        }
        refreshHomeCoins()
        return ok
    }

    fun getChainAssets() {
        request<List<AddCoinTabBean>> {
            onRequest {
                Result.Success(BWallet.get().getChainAssets())
            }
            onSuccess {
                _chainAssets.value = it
            }
        }
    }
}