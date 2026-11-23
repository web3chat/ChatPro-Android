package com.fzm.chat.core.data.dao

import androidx.room.*
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.core.data.po.MessagePO
import java.io.Serializable

/**
 * @author zhengjy
 * @since 2020/12/28
 * Description:
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(messages: List<MessagePO>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessagePO)

    @Query("SELECT * FROM chat_message WHERE logId=:logId")
    suspend fun findMessage(logId: Long): ChatMessage?

    /**
     * 按客户端消息 id 查询（服务端回推自己刚发的消息时，用于纠正本地「发送中」状态）
     */
    @Query("SELECT * FROM chat_message WHERE msgId=:msgId LIMIT 1")
    suspend fun findMessageByMsgId(msgId: String): ChatMessage?

    @Query(
        """
        SELECT c.*, u.num AS focusNum FROM chat_message c
        LEFT JOIN view_message_focus u
        ON c.logId=u.logId
        WHERE channelType=:channelType AND (`from`=:address OR target=:address) AND c.datetime < :datetime
        ORDER BY datetime DESC LIMIT :count
        
    """
    )
    suspend fun getMessageByTime(address: String, channelType: Int, datetime: Long, count: Int): List<ChatMessage>?

    /**
     * 定位消息时用：取锚点消息之后（更新）的若干条，按时间升序
     */
    @Query(
        """
        SELECT c.*, u.num AS focusNum FROM chat_message c
        LEFT JOIN view_message_focus u
        ON c.logId=u.logId
        WHERE channelType=:channelType AND (`from`=:address OR target=:address)
          AND c.datetime > :datetime
        ORDER BY datetime ASC LIMIT :count
    """
    )
    suspend fun getMessageAfter(
        address: String,
        channelType: Int,
        datetime: Long,
        count: Int
    ): List<ChatMessage>?

    @Query(
        """
        SELECT c.*, u.num AS focusNum FROM chat_message c
        LEFT JOIN view_message_focus u
        ON c.logId=u.logId
        WHERE channelType=:channelType AND (`from`=:address OR target=:address) AND c.datetime >= (SELECT datetime FROM chat_message WHERE msgId=:msgId)
        ORDER BY datetime DESC
    """
    )
    suspend fun getMessageByMsgId(address: String, channelType: Int, msgId: String): List<ChatMessage>?

    @Query(
        """
        SELECT * FROM chat_message 
        WHERE channelType=:channelType AND (`from`=:address OR target=:address) AND datetime < :datetime AND msgType=:msgType
        ORDER BY datetime DESC LIMIT :count
    """
    )
    suspend fun getOneTypeMessage(address: String, channelType: Int, datetime: Long, msgType: Int, count: Int): List<ChatMessage>?

    @Query(
        """
        SELECT * FROM chat_message 
        WHERE channelType=:channelType AND (`from`=:address OR target=:address) AND datetime < :datetime AND (msgType=:msgType1 OR msgType=:msgType2)
        ORDER BY datetime DESC LIMIT :count
    """
    )
    suspend fun getTwoTypeMessage(address: String, channelType: Int, datetime: Long, msgType1: Int, msgType2: Int, count: Int): List<ChatMessage>?

    /** 最近一段时间的指定类型消息（用于主界面启动时自动补下视频等） */
    @Query(
        """
        SELECT * FROM chat_message
        WHERE msgType=:msgType AND datetime>=:time
        ORDER BY datetime DESC LIMIT :limit
    """
    )
    suspend fun getRecentMessagesByType(msgType: Int, time: Long, limit: Int): List<ChatMessage>

    @Query("UPDATE chat_message SET state=:state WHERE msgId=:msgId")
    suspend fun updateMsgState(msgId: String, state: Int)

    @Query("UPDATE chat_message SET state=:state WHERE logId in (:logId)")
    suspend fun updateMsgState(logId: List<Long>, state: Int): Int

    @Query("UPDATE chat_message SET logId=:logId, datetime=:datetime, state=:state WHERE msgId=:msgId")
    suspend fun updateMessage(msgId: String, logId: Long, datetime: Long, state: Int)

    @Query("UPDATE chat_message SET msg=:content WHERE msgId=:msgId")
    suspend fun updateMessageContent(msgId: String, content: MessageContent)

    @Query("SELECT * FROM chat_message WHERE logId in (:logId)")
    suspend fun getMessageByLogId(logId: List<Long>): List<ChatMessage>

    @Query("DELETE FROM chat_message WHERE msgId=:msgId AND logId=:logId AND channelType=:channelType")
    suspend fun deleteMsg(msgId: String, logId: Long, channelType: Int)

    @Query("DELETE FROM message_focus_user WHERE logId=:logId")
    suspend fun deleteFocusInfo(logId: Long)

    @Transaction
    suspend fun deleteMessage(msgId: String, logId: Long, channelType: Int) {
        deleteMsg(msgId, logId, channelType)
        if (logId != 0L) {
            deleteFocusInfo(logId)
        }
    }

    @Query("SELECT logId, msgId FROM chat_message WHERE (`from`=:address OR target=:address) AND channelType=:channelType")
    suspend fun findContactMessage(address: String, channelType: Int): List<MsgId>?

    @Query(
        """
        SELECT logId, msgId FROM chat_message 
        WHERE channelType=:channelType AND (`from`=:address OR target=:address) 
        AND datetime >= :startTime AND datetime <= :endTime
    """
    )
    suspend fun findContactMessageInRange(
        address: String,
        channelType: Int,
        startTime: Long,
        endTime: Long
    ): List<MsgId>?

    @Transaction
    suspend fun deleteContactMessage(address: String, channelType: Int) {
        val list = findContactMessage(address, channelType)
        list?.forEach {
            deleteMessage(it.msgId, it.logId, channelType)
        }
    }

    @Transaction
    suspend fun deleteContactMessageInRange(address: String, channelType: Int, startTime: Long, endTime: Long) {
        findContactMessageInRange(address, channelType, startTime, endTime)?.forEach {
            deleteMessage(it.msgId, it.logId, channelType)
        }
    }

    class MsgId(
        val logId: Long,
        val msgId: String
    ) : Serializable

    /**
     * 查询指定时间之前的本地聊天记录（清理前先取出，用于删除对应的本地图片/视频文件）
     */
    @Query("SELECT * FROM chat_message WHERE datetime < :time")
    suspend fun getMessagesBefore(time: Long): List<ChatMessage>

    /**
     * 删除指定时间之前的本地聊天记录（服务端只保留 7 天，本地同步清理）。
     *
     * @return 删除条数
     */
    @Query("DELETE FROM chat_message WHERE datetime < :time")
    suspend fun deleteBefore(time: Long): Int

    /**
     * 清理没有对应消息的 @ 提醒记录，避免遗留脏数据
     */
    @Query("DELETE FROM message_focus_user WHERE logId NOT IN (SELECT logId FROM chat_message)")
    suspend fun deleteOrphanFocus(): Int
}