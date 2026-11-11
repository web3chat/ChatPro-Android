package com.fzm.chat.core.data.bean

/**
 * 与网关 [dateOpt] 一致，用于本地按相同时间窗对齐删除缓存。
 */
object PrivateChatCleanDateOpt {
    const val CUSTOM = 0
    const val LAST_1_DAY = 1
    const val LAST_3_DAYS = 2
    const val LAST_7_DAYS = 3
    const val LAST_30_DAYS = 4
    const val LAST_90_DAYS = 5
    const val LAST_180_DAYS = 6
    const val LAST_365_DAYS = 7
    const val ALL = 8

    /**
     * @return 闭区间 [start, end]（毫秒），与接口约定一致；无法解析时返回 null。
     */
    fun millisRange(
        dateOpt: Int,
        customStart: Long?,
        customEnd: Long?,
        now: Long = System.currentTimeMillis()
    ): Pair<Long, Long>? {
        return when (dateOpt) {
            CUSTOM -> {
                if (customStart != null && customEnd != null) customStart to customEnd else null
            }
            ALL -> 0L to now
            LAST_1_DAY, LAST_3_DAYS, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, LAST_180_DAYS, LAST_365_DAYS -> {
                val days = when (dateOpt) {
                    LAST_1_DAY -> 1L
                    LAST_3_DAYS -> 3L
                    LAST_7_DAYS -> 7L
                    LAST_30_DAYS -> 30L
                    LAST_90_DAYS -> 90L
                    LAST_180_DAYS -> 180L
                    LAST_365_DAYS -> 365L
                    else -> return null
                }
                val ms = days * 24L * 60 * 60 * 1000
                (now - ms) to now
            }
            else -> null
        }
    }
}
