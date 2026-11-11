package com.fzm.chat.square

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/**
 * 正方形 ImageView
 *
 * 九宫格用：宽度由布局（父容器权重）决定，高度强制等于宽度，
 * 不依赖屏幕宽度计算，避免父容器变窄（如左侧有头像）时图片被裁剪。
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
