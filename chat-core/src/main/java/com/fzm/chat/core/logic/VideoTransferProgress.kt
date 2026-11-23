package com.fzm.chat.core.logic

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * 视频发送上传进度（msgId -> 0f~1f）。
 *
 * 上传在 [com.fzm.chat.core.logic.MessageSender] 里进行，聊天页通过观察该 LiveData
 * 刷新视频消息上的百分比进度条。仅内存态，不落库。
 */
object VideoTransferProgress {

    private val _events = MutableLiveData<Pair<String, Float>>()

    val events: LiveData<Pair<String, Float>>
        get() = _events

    fun update(msgId: String, progress: Float) {
        if (msgId.isEmpty()) return
        _events.postValue(msgId to progress.coerceIn(0f, 1f))
    }
}
