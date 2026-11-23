package com.fzm.chat.biz.data.impl

import com.fzm.chat.biz.bean.ModuleState
import com.fzm.chat.biz.data.BusinessDataSource
import com.fzm.chat.biz.data.service.BusinessService
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.bean.ServerNode
import com.fzm.chat.core.net.ApiLog
import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.apiCall

/**
 * @author zhengjy
 * @since 2021/08/17
 * Description:
 */
class NetBusinessDataSource(
    private val service: BusinessService
) : BusinessDataSource {

    override suspend fun fetchModuleState(): Result<List<ModuleState>> {
        val url = "${ChatConfig.CENTRALIZED_SERVER}/app/modules/all"
        ApiLog.request("POST /app/modules/all", url)
        val result = apiCall { service.fetchModuleState() }
        ApiLog.result("POST /app/modules/all", result)
        return result
    }

    override suspend fun fetchServerList(): Result<ServerNode> {
        // 服务器管理页：拉取聊天服务 + 区块链节点列表
        val url = "${ChatConfig.CENTRALIZED_SERVER}/disc/nodes"
        ApiLog.request("GET /disc/nodes", url)
        val result = apiCall { service.fetchServerList() }
        ApiLog.result("GET /disc/nodes", result)
        // 原始响应 JSON 由 HttpLogging 打印（TAG=HttpLog，response=...）
        return result
    }
}
