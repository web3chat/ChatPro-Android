package com.fzm.chat.redpacket.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 红包金额展示：固定保留 2 位小数（四舍五入）
 *
 * 例：1.23456789 -> 1.23，1.5 -> 1.50，1 -> 1.00
 */
fun plainAmountFixed2(amountScaled: Double): String {
    return try {
        BigDecimal(amountScaled.toString()).setScale(2, RoundingMode.HALF_UP).toPlainString()
    } catch (e: Exception) {
        "0.00"
    }
}
