package com.fzm.chat.wallet.data

import com.fzm.chat.biz.base.AppConst
import com.fzm.chat.core.data.local.MemoryContactSource
import com.fzm.chat.wallet.WalletSDK
import com.fzm.chat.wallet.data.bean.ContractBean
import com.fzm.chat.wallet.data.bean.ExtendInfo
import com.fzm.chat.wallet.data.bean.TransactionInfo
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.bean.toRequestBody
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.ContractBalance
import com.fzm.wallet.sdk.db.entity.CreateRaw
import com.fzm.wallet.sdk.net.HttpResult
import com.fzm.wallet.sdk.net.apiCall
import com.fzm.wallet.sdk.net.goCall
import com.zjy.architecture.data.Result
import com.zjy.architecture.util.logD
import org.json.JSONObject

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
class WalletRepository(
    private val dataSource: WalletDataSource,
    private val memorySource: MemoryContactSource
) : WalletDataSource by dataSource {

    override suspend fun transfer(
        target: String,
        coin: Coin,
        amount: Double,
        fee: Double,
        password: String,
        note: String?
    ): Result<String> {
        return try {
            val tx = WalletSDK().transfer(coin, target, amount, fee, note, password)
            Result.Success(tx)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun getContract(
        cointype: String,
        address: String,
        extend_info: ExtendInfo
    ): Result<ContractBean> {
        return try {
            val result = dataSource.getContract(cointype, address, extend_info)
            Result.Success(result.data())
        } catch (e: Exception) {
            Result.Error(e)
        }
    }


    override suspend fun getTransactionList(
        cointype: String,
        tokensymbol: String,
        address: String,
        contractAddress: String,
        index: Long,
        count: Long,
        direction: Long,
        type: Int
    ): Result<List<TransactionInfo>> {
        try {
            val list = dataSource.getTransactionList(
                cointype,
                tokensymbol,
                address,
                contractAddress,
                index,
                count,
                direction,
                type
            )

            return Result.Success(list.data().map { tx ->
                tx.contact = memorySource.friendMap[tx.getAddress()]
                tx
            })
        } catch (e: Exception) {
            return Result.Error(e)
        }
    }

    override suspend fun getTransactionById(
        cointype: String,
        tokensymbol: String,
        contractAddress: String,
        txid: String
    ): Result<TransactionInfo?> {
        return try {
            val res = dataSource.getTransactionById(cointype, tokensymbol, contractAddress, txid)
            val tx = res.data()
            tx?.contact = memorySource.friendMap[tx?.getAddress()]
            Result.Success(tx)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun getCoinList(names: List<String>): HttpResult<List<Coin>> {
        return dataSource.getCoinList(names)
    }

    override suspend fun getBalanceByContract(
        cointype: String,
        address: String,
        contractAddress: String
    ): HttpResult<ContractBalance> {
        return dataSource.getBalanceByContract(cointype, address, contractAddress)
    }

    override suspend fun getBalanceBySymbol(
        cointype: String,
        address: String,
        tokensymbol: String
    ): HttpResult<ContractBalance> {
        return dataSource.getBalanceBySymbol(cointype, address, tokensymbol)
    }

    override suspend fun createByContract(
        cointype: String,
        tokensymbol: String,
        from: String,
        to: String,
        amount: Double,
        fee: Double,
        contractAddress: String
    ): HttpResult<CreateRaw> {
        return dataSource.createByContract(
            cointype,
            tokensymbol,
            from,
            to,
            amount,
            fee,
            contractAddress
        )
    }
}