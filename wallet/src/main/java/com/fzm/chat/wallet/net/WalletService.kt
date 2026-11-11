package com.fzm.chat.wallet.net

import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.contract.ContractRequest
import com.fzm.chat.core.data.contract.ContractResponse
import com.fzm.chat.wallet.data.bean.ContractBean
import com.fzm.chat.wallet.data.bean.TransactionInfo
import com.fzm.wallet.sdk.bean.Transactions
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.ContractBalance
import com.fzm.wallet.sdk.db.entity.CreateRaw
import com.fzm.wallet.sdk.net.GoResponse
import com.fzm.wallet.sdk.net.HttpResponse
import com.fzm.wallet.sdk.net.UrlConfig
import me.jessyan.retrofiturlmanager.RetrofitUrlManager
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
interface WalletService {

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    @POST(".")
    suspend fun transfer(@Body request: ContractRequest): ContractResponse<String>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    //@POST("https://190.92.231.38:58083")
    suspend fun getContract(@Body request: ContractRequest): ContractResponse<ContractBean>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    @POST("/")
    suspend fun queryTransactionsByAddress(@Body body: RequestBody): ContractResponse<List<TransactionInfo>>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    @POST("/")
    suspend fun queryTransactionByTxId(@Body body: RequestBody): ContractResponse<TransactionInfo>


    //查询余额
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.PHP_DOMAIN])
    @POST("interface/wallet-coin")
    suspend fun getCoinList(@Body body: RequestBody): HttpResponse<List<Coin>>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    @POST("/")
    suspend fun getBalanceBySymbol(@Body body: RequestBody): GoResponse<ContractBalance>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    @POST("/")
    suspend fun getBalanceByContract(@Body body: RequestBody): GoResponse<ContractBalance>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.WALLET_DOMAIN])
    @POST("/")
    suspend fun createByContract(@Body body: RequestBody): GoResponse<CreateRaw>
}