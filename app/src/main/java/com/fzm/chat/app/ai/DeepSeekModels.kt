package com.fzm.chat.app.ai

import com.google.gson.annotations.SerializedName

/**
 * DeepSeek API 请求体（增强版，支持流式输出 & 参数调优）
 */
data class DeepSeekRequest(
    val model: String = "deepseek-chat",
    val messages: List<Message>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("top_p")
    val topP: Double? = null
) {
    data class Message(
        val role: String,
        val content: String
    ) {
        companion object {
            fun system(content: String) = Message("system", content)
            fun user(content: String) = Message("user", content)
            fun assistant(content: String) = Message("assistant", content)
        }
    }
}

/**
 * DeepSeek API 普通响应体
 */
data class DeepSeekResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
    val error: ErrorInfo? = null
) {
    data class Choice(
        val index: Int? = null,
        val message: ResponseMessage? = null
    )

    data class ResponseMessage(
        val role: String? = null,
        val content: String? = null
    )

    data class ErrorInfo(
        val message: String? = null,
        val type: String? = null
    )

    /** 获取 AI 回复内容 */
    fun getContent(): String? {
        error?.let { return "API错误: ${it.message}" }
        return choices?.firstOrNull()?.message?.content
    }
}

/**
 * DeepSeek API 流式响应块（SSE data 行解析结果）
 */
data class DeepSeekStreamChunk(
    val id: String? = null,
    val choices: List<StreamChoice>? = null
) {
    data class StreamChoice(
        val index: Int? = null,
        val delta: DeltaMessage? = null,
        @SerializedName("finish_reason")
        val finishReason: String? = null
    )

    data class DeltaMessage(
        val role: String? = null,
        val content: String? = null
    )

    /** 提取本块中的内容增量 */
    fun getDeltaContent(): String? {
        return choices?.firstOrNull()?.delta?.content
    }

    /** 是否已结束 */
    fun isFinished(): Boolean {
        return choices?.firstOrNull()?.finishReason != null
    }
}

/**
 * 对话历史中的一条消息
 */
data class ConversationMessage(
    val content: String,
    val isUser: Boolean
)

/**
 * AI 聊天配置参数
 */
data class ChatConfig(
    /** 模型名称：deepseek-chat / deepseek-reasoner */
    val model: String = "deepseek-chat",
    /** 是否启用流式输出 */
    val stream: Boolean = true,
    /** 温度 (0~2)，越高越有创造性，越低越确定性 */
    val temperature: Double? = 0.7,
    /** 最大回复 token 数 */
    val maxTokens: Int? = 2048,
    /** 核采样 (0~1)，控制词汇多样性 */
    val topP: Double? = 0.9
)

/**
 * 应用上下文（传递给 AI 以提供个性化回答）
 */
data class AppContext(
    /** 当前用户昵称 */
    val nickname: String = "",
    /** 用户当前所在页面 */
    val currentPage: String = "",
    /** 钱包资产概览（可选） */
    val walletSummary: String = "",
    /** 额外自定义上下文 */
    val extra: String = ""
)
