package com.fzm.chat.core.data.bean

import java.io.Serializable

/**
 * 入群申请（群加群限制为"入群需审核"时）
 *
 * 字段与《审批入群 App 对接文档》一致：
 * - applyId：申请单号（通过/拒绝时回传）
 * - id：群 ID（string，不是申请单号）
 * - memberId：申请人；inviterId：邀请人（空表示自己申请）
 * - applyStatus：0 待审，1 通过，2 拒绝，10 忽略
 */
data class GroupApplyTO(
    val applyId: String? = null,
    val id: String? = null,
    val inviterId: String? = null,
    val memberId: String? = null,
    val applyNote: String? = null,
    val operatorId: String? = null,
    val applyStatus: Int = 0,
    val rejectReason: String? = null,
    val createTime: Long = 0,
    val updateTime: Long = 0
) : Serializable {

    /** 申请单号 */
    val applyRecordId: String
        get() = applyId.orEmpty()

    /** 群 ID（字符串） */
    val applyGidStr: String
        get() = id.orEmpty()

    /** 申请人地址 */
    val applyMemberId: String
        get() = memberId.orEmpty()

    /** 申请时间（毫秒） */
    val applyTime: Long
        get() = createTime

    /** 是否待审核 */
    val isWaiting: Boolean
        get() = applyStatus == 0

    /**
     * 申请列表响应体：data.applys[]
     */
    data class Wrapper(
        val applys: List<GroupApplyTO>? = null
    ) : Serializable {

        val data: List<GroupApplyTO>
            get() = applys ?: emptyList()
    }
}
