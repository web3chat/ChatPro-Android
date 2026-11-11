package com.fzm.chat.ui

import android.Manifest
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.biz.base.AppConst
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.base.shareCode
import com.fzm.chat.biz.utils.defaultCustomTabIntent
import com.fzm.chat.biz.utils.onExplain
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.databinding.ActivityFeedbackBinding
import com.fzm.chat.databinding.ActivityQrCodeBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.vm.QRCodeViewModel
import com.king.zxing.util.CodeUtils
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.ext.clipboardManager
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.isAndroidQ
import com.zjy.architecture.util.other.BarUtils
import org.koin.android.ext.android.bind
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2021/01/06
 * Description:
 */
@Route(path = MainModule.FEEDBACK)
class FeedbackActivity : BizActivity() {

    private val feedUid = "1Fiyuhhagt8XQZTrGQrksHExxi2HXNF62J"
    private val binding by init<ActivityFeedbackBinding>()

    override val root: View
        get() = binding.root

    override fun setSystemBar() {
        BarUtils.setStatusBarColor(this, resources.getColor(R.color.biz_color_accent), 0)
        BarUtils.setStatusBarLightMode(this, false)
        window?.navigationBarColor = resources.getColor(R.color.biz_color_primary)
    }

    override fun initView() {
        ARouter.getInstance().inject(this)
    }

    override fun initData() {
        binding.tvUid.text = feedUid
        val feedUidUrl = "${AppConfig.APP_DOWNLOAD_URL}/?uid=$feedUid"
        binding.ivMyQr.setImageBitmap(CodeUtils.createQRCode(feedUidUrl, 350))
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.tvCopyAddress.setOnClickListener {
            val data = ClipData.newPlainText("feedUid", feedUid)
            clipboardManager?.setPrimaryClip(data)
            toast(R.string.chat_tips_copy_address)
        }
    }


}