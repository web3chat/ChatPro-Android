package com.fzm.chat.biz.ai

import android.content.Context
import com.fzm.chat.core.data.ChatConfig as AppConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * DeepSeek AI API 客户端（增强版）
 *
 * 特性：
 * - 流式输出，用户可逐字看到回复
 * - 丰富的系统提示词，包含应用上下文
 * - 可配置 temperature / max_tokens / top_p
 * - 支持 deepseek-chat 和 deepseek-reasoner 模型切换
 * - 消息翻译：将聊天内容翻译为 App 当前设置的语言
 */
object DeepSeekClient {

    private const val BASE_URL = "https://api.deepseek.com"

    /** DeepSeek API Key：从本机 chat-pro.properties 读取，仓库不保存真实密钥 */
    private val API_KEY: String
        get() = AppConfig.DEEPSEEK_API_KEY

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // 流式输出需要更长超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ==================== 流式聊天（推荐） ====================

    /**
     * 流式聊天：逐 token 回调 AI 回复
     *
     * @param messages 对话历史（包含用户问题和 AI 历史回复）
     * @param onChunk  每次收到新 token 时回调（在 IO 线程，调用方需切到主线程更新 UI）
     * @param config   聊天配置（模型、温度等）
     * @param context  Android Context（用于读取字符串资源）
     * @param appCtx   应用上下文（用户昵称、所在页面等）
     * @return 完整回复文本，失败返回错误消息
     */
    suspend fun chatStream(
        messages: List<ConversationMessage>,
        onChunk: (String) -> Unit,
        config: ChatConfig = ChatConfig(),
        context: Context? = null,
        appCtx: AppContext = AppContext()
    ): String = withContext(Dispatchers.IO) {
        try {
            val apiMessages = mutableListOf<DeepSeekRequest.Message>()

            // 构建丰富的系统提示词
            apiMessages.add(DeepSeekRequest.Message.system(buildSystemPrompt(context, appCtx)))

            // 添加对话历史
            messages.forEach { msg ->
                apiMessages.add(
                    if (msg.isUser) DeepSeekRequest.Message.user(msg.content)
                    else DeepSeekRequest.Message.assistant(msg.content)
                )
            }

            val requestBody = DeepSeekRequest(
                model = config.model,
                messages = apiMessages,
                stream = true,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                topP = config.topP
            )
            val jsonBody = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .post(RequestBody.create(JSON, jsonBody))
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                android.util.Log.e("DeepSeekClient", "API error (${response.code}): $errorBody")
                val errorMsg = try {
                    gson.fromJson(errorBody, DeepSeekResponse::class.java).error?.message
                } catch (_: Exception) { null }
                return@withContext "[错误] ${errorMsg ?: "服务不可用 (${response.code})"}"
            }

            // 逐行读取 SSE 流
            val body = response.body ?: run {
                return@withContext "[错误] 响应为空"
            }

            val reader = BufferedReader(InputStreamReader(body.byteStream(), charset("UTF-8")))
            var line: String?
            val totalContent = StringBuilder()

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // SSE 数据行格式: "data: {...}"
                if (!currentLine.startsWith("data: ")) continue

                val data = currentLine.removePrefix("data: ").trim()

                // 流结束标记
                if (data == "[DONE]") break

                try {
                    val chunk = gson.fromJson(data, DeepSeekStreamChunk::class.java)
                    val delta = chunk.getDeltaContent()
                    if (delta != null && delta.isNotEmpty()) {
                        totalContent.append(delta)
                        onChunk(delta)  // 逐块回调
                    }
                } catch (e: Exception) {
                    // 解析失败的行跳过，不中断流
                    android.util.Log.w("DeepSeekClient", "Skip unparseable SSE line: $currentLine")
                }
            }

            // 如果整段回复为空，给出兜底
            if (totalContent.isEmpty()) {
                val fallback = "（AI 未生成回复，请换个方式提问）"
                onChunk(fallback)
                return@withContext fallback
            }
            totalContent.toString()
        } catch (e: IOException) {
            android.util.Log.e("DeepSeekClient", "Stream request failed", e)
            "抱歉，网络连接失败，请稍后重试。"
        } catch (e: Exception) {
            android.util.Log.e("DeepSeekClient", "Stream error", e)
            "抱歉，AI 服务出现异常，请稍后重试。"
        }
    }

    // ==================== 非流式聊天（兼容旧接口） ====================

    /**
     * 非流式聊天：等待完整回复后一次性返回
     *
     * @param messages     对话历史
     * @param systemPrompt 自定义系统提示词（为 null 时使用内置增强 Prompt）
     * @return AI 回复内容，失败返回 null
     */
    suspend fun chat(
        messages: List<ConversationMessage>,
        systemPrompt: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val apiMessages = mutableListOf<DeepSeekRequest.Message>()

            // 添加系统提示
            if (!systemPrompt.isNullOrBlank()) {
                apiMessages.add(DeepSeekRequest.Message.system(systemPrompt))
            } else {
                apiMessages.add(DeepSeekRequest.Message.system(buildSystemPrompt(null, AppContext())))
            }

            // 添加对话历史
            messages.forEach { msg ->
                apiMessages.add(
                    if (msg.isUser) DeepSeekRequest.Message.user(msg.content)
                    else DeepSeekRequest.Message.assistant(msg.content)
                )
            }

            val requestBody = DeepSeekRequest(
                messages = apiMessages,
                stream = false,
                temperature = 0.7,
                maxTokens = 2048,
                topP = 0.9
            )
            val jsonBody = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .post(RequestBody.create(JSON, jsonBody))
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e("DeepSeekClient", "API error: $responseBody")
                return@withContext "抱歉，AI服务暂时不可用（${response.code}）"
            }

            val deepSeekResponse = gson.fromJson(responseBody, DeepSeekResponse::class.java)
            deepSeekResponse.getContent()
        } catch (e: Exception) {
            android.util.Log.e("DeepSeekClient", "Request failed", e)
            "抱歉，网络连接失败，请稍后重试。"
        }
    }

    /**
     * 单次问答（无对话历史）
     */
    suspend fun ask(question: String, systemPrompt: String? = null): String? {
        return chat(
            messages = listOf(ConversationMessage(content = question, isUser = true)),
            systemPrompt = systemPrompt
        )
    }

    // ==================== 私有方法 ====================

    /**
     * 构建增强版系统提示词
     *
     * 包含：
     * 1. 角色设定 — ChatPro 专属 AI 助手
     * 2. 功能清单 — 告诉 AI 应用有哪些能力
     * 3. 用户上下文 — 当前用户昵称、所在页面等信息
     * 4. 回答规范 — 语气、格式、长度要求
     */
    private fun buildSystemPrompt(context: Context?, appCtx: AppContext): String {
        val sb = StringBuilder()

        // 角色设定
        sb.appendLine("小C是您的智能助手。")
        sb.appendLine("您正在使用的是一款集即时通讯、数字钱包、红包、DApp 浏览器于一体的 Web3 社交应用。")
        sb.appendLine()

        // 功能清单
        sb.appendLine("=== 您 核心功能 ===")
        sb.appendLine("- 加密聊天：支持单聊、群聊，端到端加密保护隐私")
        sb.appendLine("- 数字钱包：支持多链资产管理、转账、收款")
        sb.appendLine("- 红包：支持普通红包和链上红包（BTY/ETH/BNB 等多币种）")
        sb.appendLine("- DApp 浏览器：内置 Web3 浏览器，支持连接钱包与 DApp 交互")
        sb.appendLine("- 通讯录：好友管理、群组管理")
        sb.appendLine("- 账户安全：助记词备份、密码设置、设备管理")
        sb.appendLine("- OA 办公：企业组织架构、审批流程（部分版本）")
        sb.appendLine()

        // 用户上下文
        if (appCtx.nickname.isNotBlank() || appCtx.currentPage.isNotBlank()) {
            sb.appendLine("=== 当前用户信息 ===")
            if (appCtx.nickname.isNotBlank()) {
                sb.appendLine("- 用户昵称：${appCtx.nickname}")
            }
            if (appCtx.currentPage.isNotBlank()) {
                sb.appendLine("- 所在页面：${appCtx.currentPage}")
            }
            if (appCtx.walletSummary.isNotBlank()) {
                sb.appendLine("- 钱包概况：${appCtx.walletSummary}")
            }
            if (appCtx.extra.isNotBlank()) {
                sb.appendLine("- ${appCtx.extra}")
            }
            sb.appendLine()
        }

        // 回答规范
        sb.appendLine("=== 回答要求 ===")
        sb.appendLine("- 使用中文回答，语气亲切友好但专业")
        sb.appendLine("- 回答简洁明了，优先给出可操作的建议")
        sb.appendLine("- 涉及钱包/转账/私钥等安全问题时，务必提醒用户注意风险")
        sb.appendLine("- 如果用户问的是 App 操作问题，给出具体的菜单路径和操作步骤")
        sb.appendLine("- 如果不确定答案，诚实告知并建议联系官方客服")

        return sb.toString().trimEnd()
    }
}
