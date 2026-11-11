package com.fzm.chat.redpacket.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.View
import androidx.core.content.ContextCompat
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.redpacket.R
import com.fzm.chat.redpacket.databinding.ActivityRedDetailBinding
import com.fzm.chat.router.redpacket.RedPacketModule
import com.zjy.architecture.ext.notchSupport
import com.zjy.architecture.util.other.BarUtils
import java.lang.Exception
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * @author zhengjy
 * @since 2021/08/27
 * Description: 红包领取凭证（祝福语 + 领取金额 / 领取人 / 领取时间）
 */
@Route(path = RedPacketModule.RED_DETAIL)
class RedDetailActivity : BizActivity() {


    private val binding by init<ActivityRedDetailBinding>()

    @JvmField
    @Autowired(name = RedPacketModule.PARAM_RED_CONTENT)
    var redContent: String? = ""

    override fun setSystemBar() {
        notchSupport(window)
        // 与标题栏、页面渐变顶部同色
        BarUtils.setStatusBarColor(this, ContextCompat.getColor(this, R.color.redpacket_red_top), 0)
        BarUtils.setStatusBarLightMode(this, false)
        window?.navigationBarColor = ContextCompat.getColor(this, R.color.biz_color_primary)
    }

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
    }

    override fun initData() {
        try {
            redContent?.let { red ->
                val contents = red.split(",")
                val inAddr = contents[0]
                val inAmount = contents[1]
                val inTime = contents[2]
                binding.tvInName.text = inAddr
                renderAmount(inAmount)
                binding.tvTime.text = inTime
            }


        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    /**
     * 金额展示：数字固定保留 2 位小数，币种单位小一号（如 0.3BTY -> 0.30 BTY）
     */
    private fun renderAmount(raw: String) {
        val text = raw.trim()
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)(.*)$").find(text)
        val number = match?.groupValues?.get(1) ?: text
        val symbol = match?.groupValues?.get(2).orEmpty()
        val plain = try {
            BigDecimal(number).setScale(2, RoundingMode.HALF_UP).toPlainString()
        } catch (e: Exception) {
            number
        }
        if (symbol.isEmpty()) {
            binding.tvAmount.text = plain
            return
        }
        val ssb = SpannableStringBuilder("$plain$symbol")
        ssb.setSpan(
            AbsoluteSizeSpan(18, true),
            plain.length,
            ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvAmount.text = ssb
    }


    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
    }
}