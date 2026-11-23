package com.fzm.chat.core.net.source.impl

import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.bean.ChatQuery
import com.fzm.chat.core.data.bean.ServerGroupInfo
import com.fzm.chat.core.data.bean.UserAddress
import com.fzm.chat.core.data.bean.UserResult
import com.fzm.chat.core.data.contract.ContractRequest
import com.fzm.chat.core.data.contract.apiCall2
import com.fzm.chat.core.net.ApiLog
import com.fzm.chat.core.net.api.ContractService
import com.fzm.chat.core.net.source.ContractDataSource
import com.zjy.architecture.data.Result

/**
 * @author zhengjy
 * @since 2020/12/22
 * Description:
 */
class NetContractDataSource(
    private val service: ContractService,
) : ContractDataSource {

    override suspend fun getUser(query: ChatQuery): Result<UserResult> {
        val request = ContractRequest.createQuery(query)
        return call("chat.GetUser", request) { apiCall2 { service.getUser(request) } }
    }

    override suspend fun getFriendList(query: ChatQuery): Result<UserAddress.Wrapper> {
        val request = ContractRequest.createQuery(query)
        return call("chat.GetFriends", request) { apiCall2 { service.getFriendList(request) } }
    }

    override suspend fun getBlockList(query: ChatQuery): Result<UserAddress.Wrapper> {
        val request = ContractRequest.createQuery(query)
        return call("chat.GetBlackList", request) { apiCall2 { service.getBlockList(request) } }
    }

    override suspend fun getServerGroup(query: ChatQuery): Result<ServerGroupInfo.Wrapper> {
        val request = ContractRequest.createQuery(query)
        // 原始响应 JSON 由 HttpLogging 打印（TAG=HttpLog，response=...）
        return call("chat.GetServerGroup", request) { apiCall2 { service.getServerGroup(request) } }
    }

    override suspend fun modifyFriend(address: List<String?>, type: Int, groups: List<String>): Result<String> {
        val friendAddress = address.flatMap {
            listOf(mapOf("friendAddress" to it, "type" to type, "groups" to groups))
        }
        val request = ContractRequest.create(
            "chat.CreateRawUpdateFriendTx",
            mapOf("friends" to friendAddress)
        )
        return call("chat.CreateRawUpdateFriendTx", request) {
            apiCall2 { service.modifyFriend(request) }
        }
    }

    override suspend fun modifyBlock(address: List<String?>, type: Int): Result<String> {
        val friendAddress = address.flatMap {
            listOf(mapOf("targetAddress" to it, "type" to type))
        }
        val request = ContractRequest.create(
            "chat.CreateRawUpdateBlackTx",
            mapOf("list" to friendAddress)
        )
        return call("chat.CreateRawUpdateBlackTx", request) {
            apiCall2 { service.modifyBlock(request) }
        }
    }

    override suspend fun modifyServerGroup(groupInfo: List<ServerGroupInfo>): Result<String> {
        val groups = groupInfo.flatMap {
            listOf(mapOf("id" to it.id, "type" to it.type, "name" to it.name, "value" to it.value))
        }
        val request = ContractRequest.create(
            "chat.CreateRawUpdateServerGroupTx",
            mapOf("groups" to groups)
        )
        return call("chat.CreateRawUpdateServerGroupTx", request) {
            apiCall2 { service.modifyServerGroup(request) }
        }
    }

    /**
     * 统一打印「完整 URL + Body + 结果」，与 getServerGroup 的日志格式一致。
     * 仅在 debug 包输出（[ApiLog.enabled]）。
     */
    private suspend fun <T> call(
        api: String,
        request: ContractRequest,
        block: suspend () -> Result<T>
    ): Result<T> {
        ApiLog.request(api, AppPreference.CONTRACT_URL, request)
        val result = block()
        ApiLog.result(api, result)
        return result
    }
}
