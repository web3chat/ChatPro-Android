package com.fzm.chat.call

import androidx.fragment.app.FragmentActivity
import com.fzm.chat.R
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.widget.dialog.EasyDialog

/**
 * 通话入口：加入房间（点击邀请卡片）与结束通话（含进行中通话的切换确认）
 */
object CallLauncher {

    /**
     * 加入通话房间
     */
    fun join(activity: FragmentActivity, invite: MessageContent) {
        if (CallSession.active && CallSession.invite?.callUrl != invite.callUrl) {
            EasyDialog.Builder()
                .setHeaderTitle(activity.getString(R.string.chat_call_busy_title))
                .setContent(activity.getString(R.string.chat_call_busy_content))
                .setBottomRightText(activity.getString(R.string.biz_confirm))
                .setBottomRightClickListener {
                    CallSession.destroy()
                    CallOverlayManager.hide()
                    CallActivity.open(activity, invite)
                }
                .setBottomLeftText(activity.getString(R.string.biz_cancel))
                .create(activity)
                .show()
        } else if (CallSession.active) {
            // 已在同一房间：展开全屏通话页
            CallActivity.open(activity, null)
        } else {
            CallActivity.open(activity, invite)
        }
    }

    /**
     * 结束当前通话
     */
    fun endCall() {
        CallSession.destroy()
        CallOverlayManager.hide()
    }
}
