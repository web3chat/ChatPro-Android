package com.fzm.chat.account

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.alibaba.android.arouter.facade.annotation.Route
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.LocaleUtils
import com.fzm.chat.databinding.ActivityLanguageSelectBinding
import com.fzm.chat.router.main.MainModule
import com.zjy.architecture.ext.dp

/**
 * 多语言选择页面（与 TokenPocket 保持一致的 15 种语言）
 */
@Route(path = MainModule.LANGUAGE_SELECT)
class LanguageSelectActivity : BizActivity(), View.OnClickListener {

    private val binding by init { ActivityLanguageSelectBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    override val darkStatusColor: Boolean = true

    override fun initView() {
        buildLanguageList()
        updateSelection()
    }

    override fun initData() {
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
    }

    /**
     * 根据 LocaleUtils 维护的语言列表动态构建语言行
     */
    private fun buildLanguageList() {
        binding.llLanguageList.removeAllViews()
        val languages = LocaleUtils.supportedLanguages.entries.toList()
        languages.forEachIndexed { index, (code, name) ->
            binding.llLanguageList.addView(createLanguageRow(code, name))
            // 行间分割线（最后一行之后不添加）
            if (index < languages.size - 1) {
                binding.llLanguageList.addView(createDivider())
            }
        }
    }

    /**
     * 创建单个语言行（语言名 + 选中图标）
     */
    private fun createLanguageRow(code: String, name: String): View {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50.dp)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15.dp, 0, 15.dp, 0)
            tag = code
            setOnClickListener(this@LanguageSelectActivity)
        }
        // 使用系统自带的可点击水波纹背景
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        row.background = ContextCompat.getDrawable(this, outValue.resourceId)

        val tvName = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = name
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@LanguageSelectActivity, R.color.biz_text_grey_dark))
        }
        val ivCheck = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(20.dp, 20.dp)
            setImageResource(R.drawable.ic_lang_check_normal)
            tag = CHECK_TAG
        }
        row.addView(tvName)
        row.addView(ivCheck)
        return row
    }

    /**
     * 创建行间分割线
     */
    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1)
            ).apply { marginStart = 15.dp }
            setBackgroundColor(ContextCompat.getColor(this@LanguageSelectActivity, R.color.biz_color_divider))
        }
    }

    /**
     * 刷新各语言行的选中状态
     */
    private fun updateSelection() {
        val current = LocaleUtils.getSavedLanguage(this)
        for (i in 0 until binding.llLanguageList.childCount) {
            val child = binding.llLanguageList.getChildAt(i)
            val code = child.tag as? String ?: continue
            val check = child.findViewWithTag<ImageView>(CHECK_TAG)
            if (check != null) {
                check.setImageResource(
                    if (code == current) R.drawable.ic_lang_check_selected
                    else R.drawable.ic_lang_check_normal
                )
            }
        }
    }

    override fun onClick(v: View?) {
        val code = v?.tag as? String ?: return
        if (LocaleUtils.getSavedLanguage(this) != code) {
            LocaleUtils.changeLanguage(this, code)
            updateSelection()
            // 重启当前Activity以应用语言
            recreate()
        }
    }

    companion object {
        private const val CHECK_TAG = "lang_check"
    }
}
