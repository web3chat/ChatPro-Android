package com.fzm.chat.wallet.impl

import android.util.Log
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.utils.MMkvUtil
import com.fzm.chat.core.data.bean.Field
import com.fzm.chat.core.data.contract.ContractRequest
import com.fzm.chat.core.data.contract.apiCall2
import com.fzm.chat.core.net.source.TransactionSource
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.UserDataSource
import com.fzm.chat.wallet.data.WalletDataSource
import com.fzm.chat.wallet.data.bean.*
import com.fzm.chat.wallet.net.WalletService
import com.fzm.chat.wallet.toRequestBody
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.bean.Transactions
import com.fzm.wallet.sdk.bean.toJSONObject
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.ContractBalance
import com.fzm.wallet.sdk.db.entity.CreateRaw
import com.fzm.wallet.sdk.net.HttpResult
import com.google.gson.Gson
import com.fzm.wallet.sdk.net.apiCall
import com.fzm.wallet.sdk.net.goCall
import com.zjy.architecture.data.Result
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * @author zhengjy
 * @since 2021/08/04
 * Description:
 */
class NetWalletDataSource(
    private val delegate: LoginDelegate,
    private val transaction: TransactionSource,
    private val userSource: UserDataSource,
    private val service: WalletService
) : WalletDataSource {

    private companion object {
        const val TAG = "WalletContract"
    }

    /** 串行化链地址同步，避免并发产生重复交易 */
    private val chainAddressMutex = Mutex()

    private suspend fun createRawTransaction(
        target: String,
        execer: String,
        assets: String,
        amount: Double,
        fee: Double,
        isToken: Boolean,
        note: String?
    ): Result<String> {
        val request = ContractRequest.create(
            "Chain33.CreateRawTransaction",
            TransferParams.createRawTransaction(target, execer, assets, amount, fee, isToken, note)
        )
        return apiCall2 { service.transfer(request) }
    }
    override suspend fun getContract(
        cointype: String,
        address: String,
        extend_info: ExtendInfo,
    ): Result<ContractBean> {
        val params = GetContractParams.createContractParams(cointype, address, extend_info)
        val request = ContractRequest.create("Wallet.GetContractorInfo", params)
        // 自定义代币识别排障日志：请求参数 + 实际 body + 返回结果
        Log.d(
            TAG,
            "getContract → cointype=$cointype, address=$address, execer=${extend_info.Execer}, " +
                "baseUrl=${ChatConfig.GO_URL}"
        )
        Log.d(TAG, "getContract body=${Gson().toJson(request)}")
        val result = apiCall2 { service.getContract(request) }
        if (result.isSucceed()) {
            val bean = result.data()
            Log.d(
                TAG,
                "getContract ← symbol=${bean.symbol}, decimals=${bean.decimals}, " +
                    "contractAddr=${bean.contractAddr}"
            )
        } else {
            Log.e(TAG, "getContract ← 失败: ${result.error().message}")
        }
        return result
    }

    override suspend fun transfer(
        target: String,
        coin: Coin,
        amount: Double,
        fee: Double,
        password: String,
        note: String?
    ): Result<String> {
        throw UnsupportedOperationException("使用Native方法")
    }

    override suspend fun getAssetList(address: String): Result<List<Coin>> {
        return try {
            val coins = BWallet.get().getAllCoins()
            Result.Success(coins.filter { it.status == Coin.STATUS_ENABLE })
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
        type: Int,
    ): Result<List<TransactionInfo>> {
        return apiCall2 {
            service.queryTransactionsByAddress(
                toRequestBody(
                    "Wallet.QueryTransactionsByaddress",
                    "cointype" to cointype,
                    "address" to address,
                    "contractAddr" to contractAddress,
                    "index" to index,
                    "count" to count,
                    "direction" to direction,
                    "type" to type,
                )
            )
        }
    }

    override suspend fun getTransactionById(
        cointype: String,
        tokensymbol: String,
        contractAddress: String,
        txid: String
    ): Result<TransactionInfo?> {
        return apiCall2 {
            service.queryTransactionByTxId(
                toRequestBody(
                    "Wallet.QueryTransactionByTxid",
                    "cointype" to cointype,
                    "tokensymbol" to tokensymbol,
                    "contractAddr" to contractAddress,
                    "txid" to txid,
                )
            )
        }
    }

    override suspend fun setChainAddress(address: Map<String, String>): Result<String> = chainAddressMutex.withLock {
        // 冷启动/切换账号后用户信息 LiveData 首值可能尚未就绪，短暂等待（最多 5 秒），避免白白失败
        var user = delegate.current.value
        var waited = 0L
        while (user == null && waited < 5_000L) {
            delay(500L)
            waited += 500L
            user = delegate.current.value
        }
        val userInfo = user ?: return@withLock Result.Error(Exception("用户信息为空"))
        val syncKey = "${ChatConst.UserField.CHAIN_SYNC_PREFIX}${userInfo.address}"
        val fields = mutableListOf<Field>()
        for ((k, v) in address) {
            // 仅当链上资料缺失或值不一致时才提交，避免重复交易且支持地址变更
            val old = userInfo.chainAddress[k]
            if (old == null || old != v) {
                fields.add(Field("${ChatConst.UserField.CHAIN_PREFIX}$k", v, 1))
            }
        }
        if (fields.isEmpty()) {
            // 本地与链上已一致，视为同步成功
            MMkvUtil.encode(syncKey, true)
            Log.d("ChainSync", "无需上传（本地与链上已一致），syncKey=$syncKey")
            return@withLock Result.Success("")
        }
        Log.d("ChainSync", "待上传字段(${fields.size}): $fields")
        // 失败自动重试 3 次（指数退避），尽可能保证上传成功
        var last: Result<String> = Result.Error(Exception("同步链地址失败"))
        repeat(3) { attempt ->
            last = userSource.setUserInfo(fields)
            if (last.isSucceed()) {
                Log.d("ChainSync", "第${attempt + 1}次上传成功")
                delegate.updateInfo {
                    fields.forEach {
                        chainAddress[it.name.substring(ChatConst.UserField.CHAIN_PREFIX.length)] = it.value
                    }
                }
                MMkvUtil.encode(syncKey, true)
                // 成功必须直接返回；只 return@repeat 会继续下一次重试，
                // 成功后走到底部 last.error() 会抛
                // ClassCastException: Result$Success cannot be cast to Result$Error
                return@withLock last
            }
            val reason = (last as? Result.Error)?.e?.message ?: last.toString()
            Log.w("ChainSync", "第${attempt + 1}次上传失败：$reason")
            if (attempt < 2) delay((attempt + 1) * 2000L)
        }
        // 记录失败状态，供“我的”页展示与手动重试
        MMkvUtil.encode(syncKey, false)
        val reason = (last as? Result.Error)?.e?.message ?: last.toString()
        Log.e("ChainSync", "重试3次后收款地址同步仍失败：$reason")
        last
    }

    override suspend fun getCoinList(names: List<String>): HttpResult<List<Coin>> {
        return apiCall { service.getCoinList(com.fzm.wallet.sdk.bean.toRequestBody("names" to JSONArray(names))) }
    }

    override suspend fun getBalanceByContract(
        cointype: String,
        address: String,
        contractAddress: String
    ): HttpResult<ContractBalance> {
        val extendInfo = JSONObject()
        extendInfo.put("Execer", contractAddress)

        return goCall {
            service.getBalanceByContract(
                com.fzm.wallet.sdk.bean.toRequestBody(
                    "Wallet.GetBalance",
                    "cointype" to cointype,
                    "address" to address,
                    "extend_info" to extendInfo
                )
            )
        }
    }
    override suspend fun getBalanceBySymbol(
        cointype: String,
        address: String,
        tokensymbol: String
    ): HttpResult<ContractBalance> {
        return goCall {
            service.getBalanceBySymbol(
                com.fzm.wallet.sdk.bean.toRequestBody(
                    "Wallet.GetBalance",
                    "cointype" to cointype,
                    "address" to address,
                    "tokensymbol" to tokensymbol
                )
            )
        }
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
        val extend = JSONObject()
        extend.put("token_addr", contractAddress)
        val transaction = JSONObject()
        transaction.put("from", from)
        transaction.put("to", to)
        transaction.put("amount", amount)
        transaction.put("fee", fee)
        transaction.put("extend", extend)

        return goCall {
            service.createByContract(
                com.fzm.wallet.sdk.bean.toRequestBody(
                    "Wallet.CreateRawTransaction",
                    "cointype" to cointype,
                    "tokensymbol" to tokensymbol,
                    "transaction" to transaction
                )
            )
        }
    }

}