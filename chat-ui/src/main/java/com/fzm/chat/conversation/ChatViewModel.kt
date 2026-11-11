package com.fzm.chat.conversation

import android.media.AudioManager
import android.util.Base64
import androidx.lifecycle.*
import com.fzm.chat.R
import com.fzm.chat.bean.MuteParams
import com.fzm.chat.core.at.AtBlock
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.*
import com.fzm.chat.core.data.msg.MsgState
import com.fzm.chat.core.utils.CipherUtils
import com.zjy.architecture.data.Result
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.local.MessageRepository
import com.fzm.chat.core.data.model.*
import com.fzm.chat.core.data.po.FriendUser
import com.fzm.chat.core.data.po.GroupInfo
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.core.data.po.MessageSource
import com.fzm.chat.core.data.po.Reference
import com.fzm.chat.core.logic.MessageSender
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.repo.ChatRepository
import com.fzm.chat.core.repo.ContractRepository
import com.fzm.chat.core.repo.GroupRepository
import com.fzm.chat.core.net.model.RevokeBatchResp
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.data.UserRepository
import com.fzm.chat.router.oa.CompanyUser
import com.fzm.chat.router.oa.OAModule
import com.fzm.chat.router.oa.OAService
import com.fzm.chat.router.route
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import dtalk.biz.Biz
import dtalk.biz.msg.Msg
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author zhengjy
 * @since 2020/12/25
 * Description:
 */
class ChatViewModel(
    private val repository: ContractRepository,
    private val chatRepo: ChatRepository,
    private val groupRepo: GroupRepository,
    private val userRepo: UserRepository,
    private val contactManager: ContactManager,
    private val messageSender: MessageSender,
    delegate: LoginDelegate
) : LoadingViewModel(), LoginDelegate by delegate {

    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    private val oaService by route<OAService>(OAModule.SERVICE)

    private val _latestMessage = MutableLiveData<List<ChatMessage>>()
    val latestMessage: LiveData<List<ChatMessage>>
        get() = _latestMessage

    private val _locatePosition = MutableLiveData<Int>()
    val locatePosition: LiveData<Int>
        get() = _locatePosition

    private val _muteResult = MutableLiveData<GroupUserTO>()
    val muteResult: LiveData<GroupUserTO>
        get() = _muteResult

    private val _scrollBottom = MutableLiveData<Unit>()
    val scrollBottom: LiveData<Unit>
        get() = _scrollBottom

    private val _loadMoreState = MutableLiveData<LoadMoreState>()
    val loadMoreState: LiveData<LoadMoreState>
        get() = _loadMoreState

    // 服务端分页游标（pri-chat-record 的 logId 参数）
    private var nextCursor: String? = null
    private var serverHasMore = false

    // 本地分页游标（群聊等非私聊：当前已加载消息中最早一条的时间，null 表示没有更多）
    private var localCursor: Long? = null

    // 私聊本地模式（PRI_CHAT_LOCAL）下各会话上次与服务端静默同步的时间，用于节流
    private val priSyncTime = mutableMapOf<String, Long>()

    private val _unReadCount = MutableLiveData<Int>()
    val unReadCount: LiveData<Int>
        get() = _unReadCount

    private val _scrollPosition = MutableLiveData<Int>()
    val scrollPosition: LiveData<Int>
        get() = _scrollPosition

    private var onMessageListener: ((MessageOption) -> Unit)? = null

    private var currentTarget: String? = null

    /**
     * 首次消息列表加载完成
     *
     * 用于在消息列表加载完成之前，挂起消息订阅通知，防止在并发接收消息的瞬间进入聊天页面，导致出现消息重复或缺失问题
     *
     *        原理： 打开页面时先订阅消息通知，但是先挂起，等加载完成数据库消息后，再处理消息通知
     * 可能存在问题：无法百分百保证订阅前的所有消息都已经插入到数据库
     */
    private var initLoadReady = AtomicBoolean(false)

    @ObsoleteCoroutinesApi
    private val sendChannel = actor<MessageOption>(capacity = Channel.UNLIMITED) {
        for (option in channel) {
            while (!initLoadReady.get()) {
                yield()
            }
            if (currentTarget == null) return@actor
            if (Option.UPDATE_CONTACT == option.option || currentTarget == option.contact) {
                if (option.option == Option.ADD_MSG || option.option == Option.UPDATE_STATE) {
                    if (option.message.sender == null) {
                        option.message.sender = if (option.message.channelType == ChatConst.PRIVATE_CHANNEL) {
                            contactManager.getUserInfo(option.message.from, true)
                        } else {
                            contactManager.getGroupUserInfo(option.message.target, option.message.from)
                        }
                    }
                    option.message.reference?.also { reference ->
                        if (reference.refMsg == null) {
                            reference.refMsg = database.messageDao().findMessage(reference.ref)?.apply {
                                sender = if (channelType == ChatConst.PRIVATE_CHANNEL) {
                                    contactManager.getUserInfo(from, true)
                                } else {
                                    contactManager.getGroupUserInfo(target, from)
                                }
                            }
                        }
                    }
                }
                if (option.option == Option.ADD_MSG || option.option == Option.UPDATE_STATE ||
                    option.option == Option.SEND_FAIL
                ) {
                    val m = option.message
                    android.util.Log.d(
                        "MsgFlow",
                        "[vm-recv] option=${option.option} msgId=${m.msgId} logId=${m.logId} " +
                            "channel=${m.channelType} contact=${m.contact} type=${m.msgType} " +
                            "state=${m.state} fail=${m.failReason} detail=${m.failDetail} " +
                            "currentTarget=$currentTarget"
                    )
                }
                onMessageListener?.invoke(option)
            }
        }
    }

    init {
        MessageSubscription.registerChannel(this, sendChannel)
    }

    override fun onCleared() {
        MessageSubscription.unregisterChannel(this)
    }

    fun setCurrentTarget(address: String?) {
        currentTarget = address
    }

    fun resendMessage(url: String, message: ChatMessage) {
        messageSender.resend(url, message)
    }

    /**
     * 阅后即焚会话级模式开关（仅私聊入口，离开会话必须关掉）
     */
    private var burnMode = false

    fun setBurnMode(enabled: Boolean) {
        burnMode = enabled
    }

    fun isBurnMode(): Boolean = burnMode

    fun sendMessage(url: String, message: ChatMessage) {
        // 唯一打标点：阅后即焚模式 + 私聊 + 允许类型才打 burn 标记
        if (burnMode && message.channelType == ChatConst.PRIVATE_CHANNEL && message.canBurnType) {
            message.source = MessageSource.burnOnly(message.channelType)
        }
        android.util.Log.d("BurnFlow", "[send] burnMode=$burnMode channel=${message.channelType} type=${message.msgType} canBurn=${message.canBurnType} sourceBurn=${message.source?.burn}")
        android.util.Log.d(
            "MsgFlow",
            "[vm-send] msgId=${message.msgId} channel=${message.channelType} contact=${message.contact} " +
                "from=${message.from} target=${message.target} type=${message.msgType} " +
                "localUrl=${message.msg.localUrl} mediaUrl=${message.msg.mediaUrl} url=$url"
        )
        messageSender.send(url, message)
    }

    /**
     * 收到消息回调
     */
    fun setOnMessageListener(listener: (MessageOption) -> Unit) {
        onMessageListener = listener
    }

    /**
     * 加载最近消息
     *
     * - 私聊 + PRI_CHAT_LOCAL：本地 DB 优先加载（秒开），后台静默补库/撤回对账
     * - 私聊（未开启本地化）：服务器保存了聊天记录，从服务端加载（默认 100 条）
     * - 群聊等非私聊：服务器不保存消息，走本地 DB（旧逻辑）
     */
    fun loadMessagesFromServer(target: String, channelType: Int) {
        if (channelType == ChatConst.PRIVATE_CHANNEL && ChatConfig.PRI_CHAT_LOCAL) {
            loadPrivateMessagesFromLocal(target, channelType)
            return
        }
        if (channelType != ChatConst.PRIVATE_CHANNEL) {
            // 非私聊：服务器不保存消息，保持本地加载
            getMessageHistoryLocal(target, channelType)
            return
        }
        launch {
            serverHasMore = true
            nextCursor = null
            android.util.Log.d("MsgFlow", "[load] pri-chat-record start target=$target count=100")
            when (val result = chatRepo.getPriChatRecord(target, 100, null)) {
                is Result.Success -> {
                    val data = result.data
                    val messages = mutableListOf<ChatMessage>()
                    for (record in data.records) {
                        record.toChatMessage(channelType)?.let { messages.add(it) }
                    }
                    resolveSenders(messages, channelType)
                    _latestMessage.value = messages
                    serverHasMore = data.recordCount >= 100
                    nextCursor = data.records.lastOrNull()?.logId
                    android.util.Log.d(
                        "MsgFlow",
                        "[load] pri-chat-record ok target=$target count=${messages.size} " +
                            "recordCount=${data.recordCount} next=$nextCursor hasMore=$serverHasMore"
                    )
                }
                is Result.Error -> {
                    android.util.Log.e(
                        "MsgFlow",
                        "[load] pri-chat-record failed target=$target: ${result.error().message}"
                    )
                }
            }
            initLoadReady.compareAndSet(false, true)
            _scrollBottom.value = Unit
        }
    }

    /**
     * 分页加载更早的消息（手指下滑触发）
     *
     * - 私聊 + PRI_CHAT_LOCAL：继续从本地 DB 分页加载
     * - 私聊（未开启本地化）：走服务端分页（服务器保存了聊天记录）
     * - 群聊等非私聊：服务器不保存消息，继续从本地 DB 分页加载（旧逻辑）
     */
    fun loadMoreMessages(target: String, channelType: Int) {
        if (channelType != ChatConst.PRIVATE_CHANNEL || ChatConfig.PRI_CHAT_LOCAL) {
            loadMoreMessagesFromLocal(target, channelType)
            return
        }
        if (!serverHasMore) {
            _loadMoreState.value = LoadMoreState.End
            return
        }
        val cursor = nextCursor ?: run {
            _loadMoreState.value = LoadMoreState.End
            return
        }
        launch {
            when (val result = chatRepo.getPriChatRecord(target, 100, cursor)) {
                is Result.Success -> {
                    val data = result.data
                    val messages = mutableListOf<ChatMessage>()
                    for (record in data.records) {
                        record.toChatMessage(channelType)?.let { messages.add(it) }
                    }
                    resolveSenders(messages, channelType)
                    _latestMessage.value = messages
                    serverHasMore = data.recordCount >= 100
                    nextCursor = data.records.lastOrNull()?.logId
                    _loadMoreState.value = LoadMoreState.Complete
                }
                is Result.Error -> {
                    _loadMoreState.value = LoadMoreState.Fail
                }
            }
        }
    }

    /**
     * 本地 DB 加载（群聊等非私聊场景）
     */
    fun getMessageHistoryLocal(
        target: String,
        channelType: Int,
        datetime: Long = Long.MAX_VALUE,
        loadCount: Int = ChatConfig.PAGE_SIZE,
        needScroll: Boolean = false
    ) {
        launch {
            var pageCount = loadCount
            if (loadCount % ChatConfig.PAGE_SIZE != 0) {
                pageCount = (loadCount / ChatConfig.PAGE_SIZE + 1) * ChatConfig.PAGE_SIZE
            }
            val list = MessageRepository.getMessageByTime(target, channelType, datetime, pageCount)
            // 记录本地分页游标：下次继续往前翻页时以最早一条消息的时间为准
            localCursor = list.minOfOrNull { it.datetime }
            _latestMessage.value = list
            initLoadReady.compareAndSet(false, true)
            if (datetime == Long.MAX_VALUE) {
                _scrollBottom.value = Unit
            }
            if (needScroll) {
                _scrollPosition.value = loadCount
            }
        }
    }

    /**
     * 群聊等非私聊：从本地 DB 继续加载更早的消息
     *
     * 服务器不保存群聊消息，所以不走服务端接口，直接查本地 DB（旧逻辑）。
     * 消息列表由适配器追加到尾部（列表为倒序展示，尾部即更早的消息）。
     */
    private fun loadMoreMessagesFromLocal(target: String, channelType: Int) {
        val cursor = localCursor
        if (cursor == null) {
            _loadMoreState.value = LoadMoreState.End
            return
        }
        launch {
            val list = MessageRepository.getMessageByTime(
                target,
                channelType,
                cursor,
                ChatConfig.PAGE_SIZE
            )
            if (list.isEmpty()) {
                // 本地也没有更早的消息了
                localCursor = null
                _loadMoreState.value = LoadMoreState.End
                return@launch
            }
            localCursor = list.minOfOrNull { it.datetime }
            _latestMessage.value = list
            _loadMoreState.value = LoadMoreState.Complete
        }
    }

    /**
     * 私聊本地加载（PRI_CHAT_LOCAL 开启时）
     *
     * 与群聊一致的本地 DB 分页；本地有记录时立即展示，不等网络。
     * 随后在后台静默同步服务端最近记录：
     * - 补库：本地缺失的消息补齐（更换设备/清数据后可恢复最近历史）
     * - 撤回对账：服务端已是撤回通知、本地仍是原消息的，就地替换为撤回提示
     *
     * 本地完全没有记录时（首次安装），先同步最多 3 页（300 条）再展示。
     */
    private fun loadPrivateMessagesFromLocal(target: String, channelType: Int) {
        launch {
            try {
                val list = MessageRepository.getMessageByTime(
                    target,
                    channelType,
                    Long.MAX_VALUE,
                    ChatConfig.PAGE_SIZE
                )
                if (list.isEmpty()) {
                    // 本地没有历史：先补库再展示，否则打开会话会是空的
                    syncPrivateHistory(target, channelType, maxPages = 3)
                    val reload = MessageRepository.getMessageByTime(
                        target,
                        channelType,
                        Long.MAX_VALUE,
                        ChatConfig.PAGE_SIZE
                    )
                    localCursor = reload.minOfOrNull { it.datetime }
                    _latestMessage.value = reload
                    initLoadReady.compareAndSet(false, true)
                    _scrollBottom.value = Unit
                    return@launch
                }
                localCursor = list.minOfOrNull { it.datetime }
                _latestMessage.value = list
                initLoadReady.compareAndSet(false, true)
                _scrollBottom.value = Unit
                // 后台静默同步：换机补库 + 离线撤回对账（不阻塞 UI）
                syncPrivateHistory(target, channelType, maxPages = 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 本地加载/同步失败不应导致闪退：记录日志并放行后续消息订阅
                android.util.Log.e(
                    "MsgFlow",
                    "[load] pri local failed target=$target channel=$channelType: ${e.message}", e
                )
                initLoadReady.compareAndSet(false, true)
            }
        }
    }

    /**
     * 私聊：拉取服务端最近记录写入本地库
     *
     * @param maxPages 最多拉取页数，每页 100 条
     * @return 是否拉到了记录
     */
    private suspend fun syncPrivateHistory(
        target: String,
        channelType: Int,
        maxPages: Int
    ): Boolean = withContext(Dispatchers.IO) {
        // 后台同步 60 秒内只做一次，避免频繁进出会话重复请求；首次补库（多页）不节流
        val now = System.currentTimeMillis()
        if (maxPages <= 1 && now - (priSyncTime[target] ?: 0L) < 60_000L) return@withContext false
        priSyncTime[target] = now
        val collected = mutableListOf<ChatMessage>()
        var cursor: String? = null
        var page = 0
        while (page < maxPages) {
            val result = chatRepo.getPriChatRecord(target, 100, cursor)
            if (result !is Result.Success) break
            val data = result.data
            // 解密（native DH）放到 IO 线程，避免阻塞主线程
            for (record in data.records) {
                record.toChatMessage(channelType)?.let { collected.add(it) }
            }
            cursor = data.records.lastOrNull()?.logId
            page++
            if (data.recordCount < 100 || cursor.isNullOrEmpty()) break
        }
        if (collected.isEmpty()) return@withContext false
        // 撤回对账：离线期间被对方撤回的消息，本地要同步成撤回提示
        syncRevokedRecords(collected)
        // 补库：只插入本地没有的消息，避免用服务端记录覆盖本地发送状态/已读状态
        val existing = database.messageDao().findContactMessage(target, channelType)
            ?.mapTo(mutableSetOf()) { it.logId } ?: mutableSetOf()
        val missing = collected.filter { it.logId !in existing }
        if (missing.isNotEmpty()) {
            database.messageDao().insert(missing.map { it.toMessagePO() })
        }
        return@withContext true
    }

    /**
     * 撤回对账（离线期间被撤回的消息）
     *
     * 服务端返回的记录已是撤回通知、本地仍是原消息时，用本地原消息改造成撤回提示原地覆盖，
     * 与在线撤回信令（GroupSignalProcessor）的处理效果保持一致。
     */
    private suspend fun syncRevokedRecords(serverMessages: List<ChatMessage>) {
        serverMessages.forEach { serverMsg ->
            if (serverMsg.msgType != Biz.MsgType.Notification_VALUE) return@forEach
            if (serverMsg.msg.notificationType != Msg.NotificationType.RevokeMessage_VALUE) return@forEach
            val local = database.messageDao().getMessageByLogId(listOf(serverMsg.logId))
                .firstOrNull { it.msgType != Biz.MsgType.Notification_VALUE } ?: return@forEach
            val replaced = local.clone().apply {
                msgType = Biz.MsgType.Notification_VALUE
                msg = serverMsg.msg
                source = null
            }
            database.messageDao().insert(replaced.toMessagePO())
            MessageSubscription.onMessage(Option.REVOKE_MSG, replaced)
        }
    }

    /**
     * PriChatRecord → ChatMessage 转换（含解密）
     */
    private suspend fun PriChatRecord.toChatMessage(channelType: Int): ChatMessage? {
        val logIdLong = logId.toLongOrNull() ?: return null
        return ChatMessage(
            logId = logIdLong,
            msgId = msgId,
            channelType = channelType,
            from = fromId,
            target = targetId,
            datetime = createTime,
            state = MsgState.SENT,
            msgType = msgType,
            msg = decryptAndParse(),
            source = parseBurnSource(channelType),
            reference = null
        )
    }

    /**
     * 解析服务端历史记录中的 source：阅后即焚为 {"burn":true}，普通消息返回 null
     */
    private fun PriChatRecord.parseBurnSource(channelType: Int): MessageSource? {
        val burn = source?.get("burn") as? Boolean ?: false
        if (!burn) return null
        return MessageSource.burnOnly(channelType)
    }

    /**
     * 解密 content 中的加密数据并解析为 MessageContent
     *
     * 服务端返回的 content 格式：{"content": "<base64加密数据>"}
     * 解密使用 DH 密钥交换：CipherUtils.decrypt(data, 对方公钥, 己方私钥)
     */
    private suspend fun PriChatRecord.decryptAndParse(): MessageContent {
        val c = content ?: return MessageContent.text("")
        val encryptedBase64 = c["content"] as? String ?: return MessageContent.text("")

        return try {
            // 1. Base64 解码
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)

            // 2. 确定对方的地址（获取对方公钥）
            val myAddress = getAddress() ?: return MessageContent.text(encryptedBase64)
            val otherAddress = if (fromId == myAddress) targetId else fromId
            val otherUser = contactManager.getUserInfo(otherAddress, true)
            val otherPubKey = otherUser.getPubKey()
            val myPriKey = preference.PRI_KEY

            if (otherPubKey.isEmpty() || myPriKey.isEmpty()) {
                return MessageContent.text(encryptedBase64) // 无法解密，显示原始数据
            }

            // 3. DH 解密
            val decryptedBytes = CipherUtils.decrypt(encryptedBytes, otherPubKey, myPriKey)

            // 4. 按 msgType 解析解密后的数据
            parseDecryptedContent(decryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            // 解密失败时显示原始密文（方便调试）
            MessageContent.text(encryptedBase64)
        }
    }

    /**
     * 将解密后的字节数据按 msgType 用 protobuf 解析为 MessageContent
     *
     * 发送时每种 msgType 的 MessageContent 被序列化为对应的 Msg.* protobuf 再加密，
     * 所以解密后需要反向 parseFrom。
     */
    @Suppress("DEPRECATION")
    private fun PriChatRecord.parseDecryptedContent(decryptedBytes: ByteArray): MessageContent {
        return try {
            when (msgType) {
                Biz.MsgType.System_VALUE, Biz.MsgType.Text_VALUE -> {
                    val text = Msg.Text.parseFrom(decryptedBytes)
                    MessageContent.text(text.content, text.mentionList.ifEmpty { null })
                }
                Biz.MsgType.Audio_VALUE -> {
                    val audio = Msg.Audio.parseFrom(decryptedBytes)
                    MessageContent.audio(audio.url, null, audio.time)
                }
                Biz.MsgType.Image_VALUE -> {
                    val image = Msg.Image.parseFrom(decryptedBytes)
                    MessageContent.image(
                        image.url, null,
                        intArrayOf(image.width, image.height)
                    )
                }
                Biz.MsgType.Video_VALUE -> {
                    val video = Msg.Video.parseFrom(decryptedBytes)
                    MessageContent.video(
                        video.url, null,
                        intArrayOf(video.width, video.height), video.time
                    )
                }
                Biz.MsgType.File_VALUE -> {
                    val file = Msg.File.parseFrom(decryptedBytes)
                    MessageContent.file(file.url, null, file.name, file.size, file.md5)
                }
                Biz.MsgType.Transfer_VALUE -> {
                    val transfer = Msg.Transfer.parseFrom(decryptedBytes)
                    MessageContent.transfer(
                        transfer.chain, transfer.platform,
                        transfer.txHash, transfer.coinName, transfer.coinTypeValue
                    )
                }
                Biz.MsgType.RedPacket_VALUE -> {
                    val rp = Msg.RedPacket.parseFrom(decryptedBytes)
                    MessageContent.redPacket(
                        rp.packetId, rp.exec, rp.coinTypeValue, rp.coinName,
                        rp.remark, rp.packetTypeValue, rp.privateKey, rp.expire
                    )
                }
                Biz.MsgType.ContactCard_VALUE -> {
                    val card = Msg.ContactCard.parseFrom(decryptedBytes)
                    MessageContent.contactCard(
                        card.type, card.id, card.name, card.avatar,
                        card.server, card.inviter
                    )
                }
                Biz.MsgType.Notification_VALUE -> {
                    val notification = Msg.Notification.parseFrom(decryptedBytes)
                    MessageContent.notification("", notification.typeValue)
                }
                Biz.MsgType.RTCCall_VALUE -> {
                    // 通话记录消息 payload 为空；通话房间邀请卡片为 CallInvite
                    if (decryptedBytes.isEmpty()) {
                        MessageContent.rtcCall(0, 0)
                    } else {
                        try {
                            val invite = Msg.CallInvite.parseFrom(decryptedBytes)
                            if (invite.url.isEmpty() && invite.room.isEmpty()) {
                                MessageContent.unknown(decryptedBytes)
                            } else {
                                MessageContent.callInvite(invite.room, invite.pin, invite.mode, invite.url)
                            }
                        } catch (e: Exception) {
                            MessageContent.unknown(decryptedBytes)
                        }
                    }
                }
                else -> {
                    // 未知类型，尝试作为纯文本
                    val text = String(decryptedBytes, Charsets.UTF_8)
                    MessageContent.text(text)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 解析失败时兜底显示原始数据
            MessageContent.text(String(decryptedBytes, Charsets.UTF_8))
        }
    }

    /**
     * 为批量消息解析发送者信息
     */
    private suspend fun resolveSenders(messages: List<ChatMessage>, channelType: Int) {
        messages.forEach { msg ->
            msg.sender = if (channelType == ChatConst.PRIVATE_CHANNEL) {
                contactManager.getUserInfo(msg.from, true)
            } else {
                contactManager.getGroupUserInfo(msg.target, msg.from)
            }
        }
    }

    private suspend fun queryMessageFocus(message: ChatMessage, target: String) {
        // 查询消息自己是否关注
        if (message.logId != 0L) {
            val hasFocused = database.focusUserDao().hasFocused(message.logId, getAddress())
            MessageSubscription.onMessage(Option.UPDATE_FOCUS, MsgFocus(message.logId, 0, target, hasFocused))
        }
    }

    fun insert(message: ChatMessage) = launch {
        database.messageDao().insert(message.toMessagePO())
    }

    fun clearUnread(id: String, channelType: Int) = launch {
        database.recentSessionDao().clearUnread(id, channelType)
    }

    suspend fun getUnreadCount() = database.recentSessionDao().getUnreadCount()

    fun clearAtMsg(id: String, channelType: Int) = launch {
        database.recentSessionDao().clearAtMsg(id, channelType)
    }

    fun getUnreadCount(id: String, channelType: Int) = launch {
        val count = database.recentSessionDao().getUnreadMsgCount(id, channelType) ?: 0
        _unReadCount.value = count
    }

    fun saveDraft(text: String, aitInfo: Map<String, AtBlock>?, reference: Reference?, id: String, channelType: Int) {
        launch {
            if (text.isNotEmpty() || reference != null) {
                database.recentSessionDao().createOrUpdateDraft(Draft(text, aitInfo, reference, System.currentTimeMillis()), id, channelType)
            } else {
                database.recentSessionDao().createOrUpdateDraft(null, id, channelType)
            }
        }
    }

    fun loadDraft(id: String, channelType: Int) = liveData {
        val draft = database.recentSessionDao().getDraft(id, channelType)
        if (draft != null) {
            draft.reference?.let {
                database.messageDao().findMessage(it.ref)?.apply {
                    sender = if (channelType == ChatConst.PRIVATE_CHANNEL) {
                        contactManager.getUserInfo(from, true)
                    } else {
                        contactManager.getGroupUserInfoFast(target, from)
                    }
                    it.refMsg = this
                    emit(draft)
                }
            } ?: emit(draft)
        }
    }

    fun readAudioMessage(message: ChatMessage) {
        if (!message.msg.isRead) {
            message.msg.isRead = true
            insert(message)
        }
    }

    fun deleteMessage(message: ChatMessage) = launch {
        message.apply {
            database.recentSessionDao().deleteAndUpdate(contact, channelType, msgId, logId)
        }
    }

    fun deleteMessage(channelType: Int, address: String, logs: List<Pair<String, Long>>) = launch {
        database.recentSessionDao().deleteAndUpdate(address, channelType, logs)
    }

    fun muteUser(params: MuteParams) {
        request<GroupUserTO.Wrapper> {
            onRequest {
                groupRepo.changeMuteTime(null, params.gid, params.muteTime, listOf(params.address))
            }
            onSuccess {
                _muteResult.value = it.members[0]
            }
        }
    }

    fun fetchUserInfo(address: String?): LiveData<FriendUser> = liveData {
        if (address.isNullOrEmpty()) return@liveData
        emitSource(database.friendUserDao().getFriendLive(address))
        launch { repository.getFriendUser(address, 0, true) }
    }

    fun fetchCompanyUser(address: String?): LiveData<CompanyUser> = liveData {
        if (address.isNullOrEmpty()) return@liveData
        emitSource(userRepo.getCompanyUserLive(address))
        launch { oaService?.getCompanyUser(address) }
    }

    fun fetchGroupInfo(gid: String?): LiveData<GroupInfo> = liveData {
        if (gid.isNullOrEmpty()) return@liveData
        emitSource(database.groupDao().getGroupInfoLive(gid.toLong()))
        launch { groupRepo.getGroupInfo(null, gid.toLong()) }
    }

    /************************消息弹出菜单操作************************/

    private val _deleteMessage = MutableLiveData<ChatMessage>()
    val deleteMessage: LiveData<ChatMessage>
        get() = _deleteMessage

    private val _muteUser = MutableLiveData<MuteParams>()
    val muteUser: LiveData<MuteParams>
        get() = _muteUser

    private val _muteTips = MutableLiveData<Int>()
    val muteTips: LiveData<Int>
        get() = _muteTips

    private val _selectMode = MutableLiveData<Boolean>()
    val selectMode: LiveData<Boolean>
        get() = _selectMode

    private val _forwardMsg = MutableLiveData<ChatMessage>()
    val forwardMsg: LiveData<ChatMessage>
        get() = _forwardMsg

    private val _refMsg = MutableLiveData<ChatMessage>()
    val refMsg: LiveData<ChatMessage>
        get() = _refMsg

    private val _revokeBatchTips = MutableLiveData<String>()
    val revokeBatchTips: LiveData<String>
        get() = _revokeBatchTips

    fun requestDelete(message: ChatMessage) {
        _deleteMessage.value = message
    }

    fun requestMute(gid: Long, address: String) {
        launch {
            val user = contactManager.getGroupUserInfoFast(gid.toString(), address)
            if (user.role > GroupUser.LEVEL_USER) {
                _muteTips.value = R.string.chat_tips_can_not_mute
                return@launch
            }
            _muteUser.value = MuteParams(gid, address, user.muteTime, user.getDisplayName())
        }
    }

    fun switchAudioChannel(): Int {
        val currentMode = preference.AUDIO_CHANNEL
        if (currentMode == AudioManager.MODE_NORMAL) {
            preference.AUDIO_CHANNEL = AudioManager.MODE_IN_CALL
        } else {
            preference.AUDIO_CHANNEL = AudioManager.MODE_NORMAL
        }
        return preference.AUDIO_CHANNEL
    }

    fun requestSelect(select: Boolean) {
        _selectMode.value = select
    }

    fun requestForward(message: ChatMessage) {
        _forwardMsg.value = message
    }

    fun requestReference(message: ChatMessage) {
        _refMsg.value = message
    }

    fun requestFocus(message: ChatMessage) {
        request<Any> {
            onRequest { chatRepo.focusMessage(message.channelType, message.logId) }
        }
    }

    fun requestRevoke(message: ChatMessage) {
        request<Any> {
            onRequest { chatRepo.revokeMessage(message.channelType, message.logId) }
            onSuccess {
                // 本地构建撤回通知并立即更新UI（不依赖后台信令下发）
                launch {
                    val notificationMsg = buildRevokeNotification(message)
                    database.recentSessionDao().insertMessageReplace(notificationMsg.toMessagePO(), 0, false)
                    MessageSubscription.onMessage(Option.REVOKE_MSG, notificationMsg)
                    // 1小时后自动删除撤回提示（倒计时不展示）
                    scheduleRevokeTipAutoDelete(notificationMsg)
                }
            }
        }
    }

    /**
     * 批量撤回消息（多选模式）
     *
     * 由于后台接口限制，同时选中自己发的和对方发的消息时，需要分开两次调用：
     * 先撤回自己发的所有消息，再撤回对方发的消息。
     *
     * @param channelType 会话类型，0私聊 1群聊
     * @param messages    待撤回的消息列表
     */
    fun requestRevokeBatch(channelType: Int, messages: List<ChatMessage>) {
        val allLogIds = messages.map { it.logId }.filter { it != 0L }.distinct()
        if (allLogIds.isEmpty()) return

        // 按发送者分离：自己发的 vs 对方发的
        val selfMessages = messages.filter { it.isSendType }
        val otherMessages = messages.filter { !it.isSendType }

        // 只有一种消息，直接调用原有单次逻辑
        if (selfMessages.isEmpty() || otherMessages.isEmpty()) {
            val logIds = messages.map { it.logId }.filter { it != 0L }.distinct()
            request<RevokeBatchResp> {
                onRequest { chatRepo.revokeMessageBatch(channelType, logIds) }
                onSuccess { resp ->
                    _revokeBatchTips.value = buildRevokeTips(resp)
                    if (resp.successCount > 0) {
                        // 本地更新每条成功撤回的消息UI
                        emitLocalRevokeNotifications(messages, resp.succeeded)
                        requestSelect(false)
                    }
                }
            }
            return
        }

        // 自己发的和对方发的消息都有，分开两次调用
        val selfLogIds = selfMessages.map { it.logId }.filter { it != 0L }.distinct()
        val otherLogIds = otherMessages.map { it.logId }.filter { it != 0L }.distinct()

        request<RevokeBatchResp> {
            onRequest {
                // 先撤回自己发的消息
                val selfResp = chatRepo.revokeMessageBatch(channelType, selfLogIds)
                // 再撤回对方发的消息
                val otherResp = chatRepo.revokeMessageBatch(channelType, otherLogIds)
                // 合并两次调用的结果
                mergeBatchResults(selfResp, selfLogIds.size, otherResp, otherLogIds.size)
            }
            onSuccess { resp ->
                _revokeBatchTips.value = buildRevokeTips(resp)
                if (resp.successCount > 0) {
                    // 本地更新每条成功撤回的消息UI
                    emitLocalRevokeNotifications(messages, resp.succeeded)
                    requestSelect(false)
                }
            }
        }
    }

    /**
     * 本地发送撤回通知，更新每条成功撤回消息的UI
     * 同时写入数据库，确保重启后仍然显示撤回状态
     */
    private fun emitLocalRevokeNotifications(messages: List<ChatMessage>, succeededLogIds: List<Long>) {
        launch {
            val succeededSet = succeededLogIds.toSet()
            messages.filter { it.logId in succeededSet }.forEach { msg ->
                val notificationMsg = buildRevokeNotification(msg)
                database.recentSessionDao().insertMessageReplace(notificationMsg.toMessagePO(), 0, false)
                MessageSubscription.onMessage(Option.REVOKE_MSG, notificationMsg)
                // 1小时后自动删除撤回提示（倒计时不展示）
                scheduleRevokeTipAutoDelete(notificationMsg)
            }
        }
    }

    /**
     * 撤回提示自动删除
     *
     * 撤回成功后展示"你撤回了一条消息/对方消息/成员消息"提示，倒计时1小时后自动从本地数据库
     * 及聊天界面删除该提示。倒计时仅内部运行，界面上不展示。
     *
     * @param notificationMsg 撤回通知消息
     */
    private fun scheduleRevokeTipAutoDelete(notificationMsg: ChatMessage) {
        launch {
            delay(ChatConfig.REVOKE_TIP_EXPIRE)
            database.recentSessionDao().deleteAndUpdate(
                notificationMsg.contact,
                notificationMsg.channelType,
                notificationMsg.msgId,
                notificationMsg.logId
            )
            MessageSubscription.onDeleteMessage(notificationMsg)
        }
    }

    /**
     * 构建撤回通知消息
     *
     * @param message 原始消息
     * @return 通知类型的 ChatMessage，内容为撤回提示文案
     */
    private fun buildRevokeNotification(message: ChatMessage): ChatMessage {
        val self = getAddress() == message.from
        val otherText = if (message.channelType == ChatConst.PRIVATE_CHANNEL) "对方消息" else "成员消息"
        val content = "你撤回了一条${if (self) "消息" else otherText}"
        val msgContent = if (message.msgType == Biz.MsgType.Text_VALUE && self) {
            // 自己撤回自己发的文本消息时，保留原文以便重新编辑
            MessageContent.notification(content, message.msg.content, Msg.NotificationType.RevokeMessage_VALUE)
        } else {
            MessageContent.notification(content, Msg.NotificationType.RevokeMessage_VALUE)
        }
        return message.clone().apply {
            msgType = Biz.MsgType.Notification_VALUE
            msg = msgContent
            source = null
        }
    }

    /**
     * 合并两次批量撤回的结果
     */
    private fun mergeBatchResults(
        self: Result<RevokeBatchResp>,
        selfCount: Int,
        other: Result<RevokeBatchResp>,
        otherCount: Int
    ): Result<RevokeBatchResp> {
        val selfSuccess = self.dataOrNull()?.successCount ?: 0
        val selfFailed = self.dataOrNull()?.failedCount ?: selfCount
        val otherSuccess = other.dataOrNull()?.successCount ?: 0
        val otherFailed = other.dataOrNull()?.failedCount ?: otherCount

        val totalSuccess = selfSuccess + otherSuccess
        val totalFailed = selfFailed + otherFailed
        val totalSucceeded = (self.dataOrNull()?.succeeded ?: emptyList()) +
                (other.dataOrNull()?.succeeded ?: emptyList())
        val totalFailedItems = (self.dataOrNull()?.failed ?: emptyList()) +
                (other.dataOrNull()?.failed ?: emptyList())

        return Result.Success(RevokeBatchResp(
            total = selfCount + otherCount,
            successCount = totalSuccess,
            failedCount = totalFailed,
            succeeded = totalSucceeded,
            failed = totalFailedItems
        ))
    }

    /**
     * 根据撤回结果生成提示文案
     */
    private fun buildRevokeTips(resp: RevokeBatchResp): String {
        return when {
            resp.failedCount == 0 -> "已撤回 ${resp.successCount} 条消息"
            resp.successCount == 0 -> "撤回失败"
            else -> "成功 ${resp.successCount} 条，失败 ${resp.failedCount} 条"
        }
    }

    /**
     * 加载更多状态
     */
    enum class LoadMoreState {
        Complete, End, Fail
    }
}