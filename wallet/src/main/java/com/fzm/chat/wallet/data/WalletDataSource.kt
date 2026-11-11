package com.fzm.chat.wallet.data

import com.fzm.chat.wallet.data.bean.ContractBean
import com.fzm.chat.wallet.data.bean.ExtendInfo
import com.fzm.chat.wallet.data.bean.TransactionInfo
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.ContractBalance
import com.fzm.wallet.sdk.db.entity.CreateRaw
import com.fzm.wallet.sdk.net.HttpResult
import com.zjy.architecture.data.Result

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
interface WalletDataSource {

    /**
     * 转账方法（适用于多种主链，需要消耗手续费）
     *
     * @param target    转账对象
     * @param coin      币种
     * @param amount    转账数量
     * @param fee       矿工费
     * @param password  密码
     * @param note      上链备注
     *
     */
    suspend fun transfer(target: String, coin: Coin, amount: Double, fee: Double, password: String, note: String? = null): Result<String>
    suspend fun getContract(cointype: String,
                            address: String,
                            extend_info: ExtendInfo): Result<ContractBean>

    /**
     * 获取资产余额
     *
     * @param address    查询地址
     *
     */
    suspend fun getAssetList(address: String): Result<List<Coin>>

    /**
     * 获取交易记录
     */
    suspend fun getTransactionList(cointype: String,
                                   tokensymbol: String,
                                   address: String,
                                   contractAddress: String,
                                   index: Long,
                                   count: Long,
                                   direction: Long,
                                   type: Int): Result<List<TransactionInfo>>

    /**
     * 获取交易记录
     */
    suspend fun getTransactionById( cointype: String,
                                    tokensymbol: String,
                                    contractAddress: String,
                                    txid: String): Result<TransactionInfo?>
    suspend fun getCoinList(names: List<String>): HttpResult<List<Coin>>

    suspend fun getBalanceByContract(
        cointype: String,
        address: String,
        contractAddress: String
    ): HttpResult<ContractBalance>

    suspend fun getBalanceBySymbol(
        cointype: String,
        address: String,
        tokensymbol: String
    ): HttpResult<ContractBalance>
    suspend fun createByContract(
        cointype: String,
        tokensymbol: String,
        from: String,
        to: String,
        amount: Double,
        fee: Double,
        contractAddress: String
    ): HttpResult<CreateRaw>

    /**
     * 上传用户主链地址
     */
    suspend fun setChainAddress(address: Map<String, String>): Result<String>
}