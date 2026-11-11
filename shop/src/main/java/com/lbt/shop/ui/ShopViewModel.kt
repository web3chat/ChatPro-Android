package com.lbt.shop.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.lbt.shop.net.ShopRepository
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request

/**
 * @author zhengjy
 * @since 2021/10/23
 * Description:
 */
class ShopViewModel(
    private val repository: ShopRepository,
    private val delegate: LoginDelegate
) : LoadingViewModel() {

    private val walletService by route<WalletService>(WalletModule.SERVICE)

    private val _shopAddress by lazy { MutableLiveData<String>() }
    val shopAddress: LiveData<String>
        get() = _shopAddress

    private val _transferResult by lazy { MutableLiveData<String>() }
    val transferResult: LiveData<String>
        get() = _transferResult

    fun getShopAddress() {
        if (delegate.preference.SHOP_ADDRESS.isNotEmpty()) {
            _shopAddress.value = delegate.preference.SHOP_ADDRESS
            return
        }
        request<String>(cancelable = false) {
            onRequest { repository.getShopAddress(delegate.getAddress() ?: "") }
            onSuccess {
                _shopAddress.value = it
                if (it.isNotEmpty()) {
                    delegate.preference.SHOP_ADDRESS = it
                }
            }
            onFail { _shopAddress.value = "" }
        }
    }

    fun transfer(target: String, symbol: String, amount: Double) {
        request<String> {
            onRequest {
                try {
                    TODO("转账方法需重新实现")
//                    val tx = walletService?.transfer(target, symbol, amount)
//                    if (tx.isNullOrEmpty()) {
//                        Result.Error(ApiException("交易哈希为空"))
//                    } else {
//                        Result.Success(tx)
//                    }
                } catch (e: Exception) {
                    Result.Error(e)
                }
            }
            onSuccess {
                _transferResult.value = it
            }
        }
    }
}