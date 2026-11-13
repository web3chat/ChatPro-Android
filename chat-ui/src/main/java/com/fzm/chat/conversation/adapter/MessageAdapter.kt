package com.fzm.chat.conversation.adapter

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseProviderMultiAdapter
import com.chad.library.adapter.base.module.LoadMoreModule
import com.fzm.chat.conversation.adapter.msg.*
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.getGroupRole
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.model.GroupUser
import com.fzm.chat.core.data.model.focusUserNum
import com.fzm.chat.core.data.model.hasSent
import com.fzm.chat.core.data.model.isCallInvite
import com.fzm.chat.core.data.model.isSendType
import com.fzm.chat.core.data.model.shouldShowBurnPlaceholder
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.router.redpacket.RedPacketModule
import com.zjy.architecture.di.rootScope
import dtalk.biz.Biz

/**
 * @author zhengjy
 * @since 2020/12/25
 * Description:
 */
class MessageAdapter : BaseProviderMultiAdapter<ChatMessage>(), LoadMoreModule {

    private val innerClickListener = object : ChatBaseItem.ChatMessageClickListener {
        override fun onTouchChatMainView(view: View, event: MotionEvent?): Boolean {
            return messageEventListener?.onTouchChatMainView(view, event) ?: false
        }

        override fun onResendClick(view: View, message: ChatMessage) {
            messageEventListener?.onResendClick(view, message)
        }

        override fun onChatLayoutClick(view: View, message: ChatMessage, item: ChatBaseItem) {
            // 只有「红包链接卡片」才进红包详情：contactType = -1 的消息由 sendGroupSystem()
            // 创建（contactId 固定 -1），普通个人名片(1)/群名片(2)/团队名片(3) 不能走这里，
            // 否则会先弹红包详情页，再被 ChatActivity 的分发逻辑跳去名片页。
            if (Biz.MsgType.forNumber(message.msgType) == Biz.MsgType.ContactCard &&
                message.msg.contactType == -1
            ) {
                ARouter.getInstance()
                    .build(RedPacketModule.RED_DETAIL)
                    .withString(RedPacketModule.PARAM_RED_CONTENT, message.msg.contactName)
                    .navigation()
                return
            }
            messageEventListener?.onChatLayoutClick(view, message, item)
        }

        override fun onChatLayoutDoubleTap(view: View, message: ChatMessage) {
            messageEventListener?.onChatLayoutDoubleTap(view, message)
        }

        override fun onChatLayoutLongClick(view: View, message: ChatMessage, item: ChatBaseItem): Boolean {
            return messageEventListener?.onChatLayoutLongClick(view, message, item) ?: false
        }

        override fun onAvatarClick(view: View, message: ChatMessage) {
            messageEventListener?.onAvatarClick(view, message)
        }

        override fun onAvatarLongClick(view: View, message: ChatMessage): Boolean {
            return messageEventListener?.onAvatarLongClick(view, message) ?: false
        }

        override fun onNotificationClick(view: View, message: ChatMessage, type: Int, index: Int) {
            messageEventListener?.onNotificationClick(view, message, type, index)
        }

        override fun onReferenceClick(view: View, message: ChatMessage) {
            messageEventListener?.onReferenceClick(view, message)
        }

        override fun onFocusClick(view: View, message: ChatMessage) {
            messageEventListener?.onFocusClick(view, message)
        }

        override fun onTranslateFold(message: ChatMessage) {
            messageEventListener?.onTranslateFold(message)
        }

        override fun onMessageSelectedChanged(message: ChatMessage, max: Boolean) {
            messageEventListener?.onMessageSelectedChanged(message, max)
        }
    }

    private var messageEventListener: ChatBaseItem.ChatMessageClickListener? = null
    internal var selectable = false
    internal val delegate by rootScope.inject<LoginDelegate>()

    init {
        loadMoreModule.isEnableLoadMoreIfNotFullPage = false
        loadMoreModule.isAutoLoadMore = true
        setDiffCallback(ItemCallback())
        addItemProvider(ChatSystem())
        addItemProvider(ChatText(innerClickListener))
        addItemProvider(ChatAudio(innerClickListener))
        addItemProvider(ChatImage(innerClickListener))
        addItemProvider(ChatVideo(innerClickListener))
        addItemProvider(ChatFile(innerClickListener))
        addItemProvider(ChatNotification(innerClickListener))
        addItemProvider(ChatForward(innerClickListener))
        addItemProvider(ChatRTC(innerClickListener))
        addItemProvider(ChatCallInvite(innerClickListener))
        addItemProvider(ChatTransfer(innerClickListener))
        addItemProvider(ChatRedPacket(innerClickListener))
        //addItemProvider(ChatRedPacket2(innerClickListener))
        addItemProvider(ChatContactCard(innerClickListener))
        // 阅后即焚占位气泡（收方未阅读时展示）
        addItemProvider(ChatBurnPlaceholder(innerClickListener))

        addItemProvider(ChatUnsupported())
    }

    override fun getItemType(data: List<ChatMessage>, position: Int): Int {
        val msg = data[position]
        // 收方未阅读的阅后即焚消息：用本地占位类型展示
        if (msg.shouldShowBurnPlaceholder()) {
            android.util.Log.d("BurnFlow", "[adapter] placeholder: logId=${msg.logId} type=${msg.msgType} isSend=${msg.isSendType}")
            return ChatConst.BURN_PLACEHOLDER_TYPE
        }
        // 通话房间邀请卡片：用本地卡片类型展示（与通话记录气泡区分）
        if (msg.isCallInvite) {
            return ChatConst.CALL_INVITE_TYPE
        }
        val msgType = msg.msgType
        val type = Biz.MsgType.forNumber(msgType)
        return if (type != null) {
            msgType
        } else {
            // 不支持的消息类型
            ChatConst.UNSUPPORTED_MSG_TYPE
        }
    }

    fun setSelectable(selectable: Boolean) {
        if (this.selectable == selectable) return
        if (!selectable) {
            data.forEach { it.isSelected = false }
        }
        this.selectable = selectable
        notifyDataSetChanged()
    }

    fun isSelectable() = this.selectable

    fun setMessageEventListener(listener: ChatBaseItem.ChatMessageClickListener) {
        this.messageEventListener = listener
    }

    override fun setDiffNewData(list: MutableList<ChatMessage>?) {
        configMessageTime(list)
        super.setDiffNewData(list)
    }

    fun setDiffNewData(list: MutableList<ChatMessage>?, commitCallback: Runnable) {
        configMessageTime(list)
        if (hasEmptyView()) {
            // If the current view is an empty view, set the new data directly without diff
            setNewInstance(list)
            return
        }
        getDiffer().submitList(list, commitCallback)
    }

    override fun setDiffNewData(diffResult: DiffUtil.DiffResult, list: MutableList<ChatMessage>) {
        configMessageTime(list)
        super.setDiffNewData(diffResult, list)
    }

    override fun setData(index: Int, data: ChatMessage) {
        if (index >= this.data.size) {
            return
        }
        this.data[index] = data
        configMessageTime(this.data)
        notifyItemChanged(index + headerLayoutCount)
    }

    override fun addData(position: Int, data: ChatMessage) {
        this.data.add(position, data)
        configMessageTime(this.data)
        notifyItemInserted(position + headerLayoutCount)
        compatibilityDataSizeChanged(1)
    }

    override fun addData(data: ChatMessage) {
        this.data.add(data)
        configMessageTime(this.data)
        notifyItemInserted(this.data.size + headerLayoutCount)
        compatibilityDataSizeChanged(1)
    }

    override fun addData(newData: Collection<ChatMessage>) {
        this.data.addAll(newData)
        configMessageTime(this.data)
        notifyItemRangeChanged(this.data.size - newData.size + headerLayoutCount, newData.size)
        compatibilityDataSizeChanged(newData.size)
    }

    /**
     * 判断消息时间是否显示
     */
    fun configMessageTime(list: List<ChatMessage>?) {
        if (list == null) return
        var last: Long = 0
        list.asReversed().forEachIndexed { i, msg ->
            if (i == 0) {
                last = msg.datetime
                msg.showTime = true
            } else {
                val cur: Long = msg.datetime
                if (cur - last > 60 * 10 * 1000) {
                    last = cur
                    msg.showTime = true
                } else {
                    msg.showTime = false
                }
            }
        }
    }

    inner class ItemCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(old: ChatMessage, new: ChatMessage): Boolean {
            return (old.logId == new.logId && old.hasSent) || old.msgId == new.msgId
        }

        override fun areContentsTheSame(old: ChatMessage, new: ChatMessage): Boolean {
            return old.hashCode() == new.hashCode()
        }

        override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage): Any? {
            val bundle = Bundle()
            if (oldItem.showTime != newItem.showTime) {
                bundle.putString(ChatMessage.MSG_TIME, "")
            }
            if (oldItem.sender?.getDisplayImage() != newItem.sender?.getDisplayImage()) {
                bundle.putString(ChatMessage.MSG_AVATAR, newItem.sender?.getDisplayImage())
            }
            if (oldItem.sender?.getGroupRole(GroupUser.LEVEL_USER) != newItem.sender?.getGroupRole(GroupUser.LEVEL_USER)) {
                bundle.putInt(ChatMessage.MSG_TAG, newItem.sender?.getGroupRole(GroupUser.LEVEL_USER)
                    ?: GroupUser.LEVEL_USER)
            }
            if (oldItem.sender?.getDisplayName() != newItem.sender?.getDisplayName()) {
                bundle.putString(ChatMessage.MSG_NICKNAME, newItem.sender?.getDisplayName())
            }
            if (oldItem.state != newItem.state) {
                bundle.putInt(ChatMessage.MSG_STATE, newItem.state)
            }
            if (oldItem.msg.isRead != newItem.msg.isRead) {
                bundle.putInt(ChatMessage.MSG_READ_STATE, if (newItem.msg.isRead) 1 else 0)
            }
            if (oldItem.progress != oldItem.progress) {
                bundle.putFloat(ChatMessage.MSG_PROGRESS, newItem.progress)
            }
            if (oldItem.source != newItem.source) {
                bundle.putSerializable(ChatMessage.MSG_SOURCE, newItem.source)
            }
            if (oldItem.reference != newItem.reference) {
                bundle.putSerializable(ChatMessage.MSG_REFERENCE, newItem.reference)
            }
            if (oldItem.focusNum != newItem.focusNum) {
                bundle.putInt(ChatMessage.MSG_FOCUS, newItem.focusUserNum)
            }
            if (bundle.isEmpty) {
                return null
            }
            return bundle
        }
    }
}