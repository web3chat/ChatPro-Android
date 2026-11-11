package com.fzm.chat.square

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.fzm.chat.R
import com.fzm.chat.adapter.DialogOption
import com.fzm.chat.adapter.DialogOptionAdapter
import com.fzm.chat.biz.QRCodeHelper
import com.fzm.chat.biz.base.AppConfig
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.ImageUtils
import com.fzm.chat.biz.utils.QRCodeUtils
import com.fzm.chat.biz.utils.onExplain
import com.fzm.chat.databinding.ActivitySquareImagePreviewBinding
import com.fzm.chat.widget.BottomListDialog
import com.github.chrisbanes.photoview.PhotoView
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.ext.dp
import com.zjy.architecture.ext.haptic
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.other.BarUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 广场图片大图预览
 *
 * 支持左右滑动切换、双指缩放；点击图片或返回键退出；长按图片弹出「保存 / 识别二维码」菜单。
 */
class SquareImagePreviewActivity : BizActivity() {

    companion object {
        private const val EXTRA_IMAGES = "images"
        private const val EXTRA_INDEX = "index"

        fun start(context: Context, images: List<String>, index: Int) {
            if (images.isEmpty()) return
            context.startActivity(
                Intent(context, SquareImagePreviewActivity::class.java)
                    .putStringArrayListExtra(EXTRA_IMAGES, ArrayList(images))
                    .putExtra(EXTRA_INDEX, index)
            )
        }
    }

    private val binding by init { ActivitySquareImagePreviewBinding.inflate(layoutInflater) }

    private val images = arrayListOf<String>()

    private var bottomDialog: BottomListDialog? = null

    /** 长按的图片（识别二维码时从它的 drawable 取图，避免重新下载） */
    private var pressedPhotoView: PhotoView? = null

    private val dialogAdapter by lazy { DialogOptionAdapter(this) }

    override val root: View
        get() = binding.root

    override fun setSystemBar() {
        BarUtils.setStatusBarColor(this, Color.BLACK, 0)
        BarUtils.setStatusBarLightMode(this, false)
    }

    override fun initView() {
        intent?.getStringArrayListExtra(EXTRA_IMAGES)?.let { images.addAll(it) }
        if (images.isEmpty()) {
            finish()
            return
        }
        binding.vpImage.adapter = PreviewAdapter()
        binding.vpImage.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                showIndex(position)
            }
        })
        val index = (intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0).coerceIn(0, images.size - 1)
        binding.vpImage.currentItem = index
        showIndex(index)
    }

    override fun initData() {
        // 长按图片弹出的菜单（保存 / 识别二维码，与聊天大图浏览保持一致）
        dialogAdapter.setData(
            mutableListOf(
                DialogOption(getString(R.string.biz_save)) { saveCurrentImage() },
                DialogOption(getString(R.string.chat_action_recognize_qrcode)) { recognizeCurrentImage() }
            )
        )
    }

    override fun onDestroy() {
        bottomDialog?.dismiss()
        bottomDialog = null
        super.onDestroy()
    }

    override fun setEvent() {

    }

    /**
     * 长按图片：弹出操作菜单（保存 / 识别二维码）
     */
    private fun showImageMenu() {
        if (images.isEmpty()) return
        bottomDialog?.cancel()
        bottomDialog = BottomListDialog.create(this, dialogAdapter, 360.dp).apply {
            setCancelVisible(true)
            setOnItemClickListener { parent, _, position, _ ->
                val option = parent.getItemAtPosition(position) as DialogOption
                option.action.invoke()
                bottomDialog?.cancel()
            }
            show()
        }
    }

    /**
     * 保存当前图片到系统相册（Pictures/<应用名>）
     *
     * 直接复用已加载的图片走 MediaStore 插入相册，与网页长按保存图片行为一致；
     * 之前用 DownloadManager2 存 Downloads 集合，在部分机型上插入会失败（无任何文件）。
     */
    private fun saveCurrentImage() {
        val drawable = pressedPhotoView?.drawable
        if (drawable == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            toast(R.string.biz_tips_image_save_fail)
            return
        }
        PermissionX.init(this)
            .permissions(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            .onExplain(this)
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    toast(R.string.permission_not_granted)
                    return@request
                }
                lifecycleScope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        ImageUtils.saveImageToGallery(
                            this@SquareImagePreviewActivity,
                            drawable,
                            AppConfig.APP_NAME_EN
                        )
                    }
                    toast(
                        if (uri != null) R.string.biz_tips_image_save_success
                        else R.string.biz_tips_image_save_fail
                    )
                }
            }
    }

    /**
     * 识别当前图片中的二维码
     *
     * 从长按的 PhotoView 取已加载的图片（不重新下载），解码成功后交给 [QRCodeHelper] 统一处理（链接/复制等）
     */
    private fun recognizeCurrentImage() {
        val drawable = pressedPhotoView?.drawable
        if (drawable == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            toast(R.string.chat_tips_not_contain_qr_code)
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                QRCodeUtils.parseCode(ImageUtils.drawable2Bitmap(drawable))
            }
            if (text.isNullOrEmpty()) {
                toast(R.string.chat_tips_not_contain_qr_code)
            } else {
                QRCodeHelper.process(this@SquareImagePreviewActivity, text)
            }
        }
    }

    private fun showIndex(position: Int) {
        binding.tvIndex.text = getString(R.string.square_image_index, position + 1, images.size)
    }

    private inner class PreviewAdapter : PagerAdapter() {

        override fun getCount(): Int = images.size

        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val photoView = PhotoView(container.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnViewTapListener { _, _, _ -> finish() }
                // 长按图片 → 弹出菜单（保存 / 识别二维码）
                setOnLongClickListener { view ->
                    view.haptic(HapticFeedbackConstants.LONG_PRESS)
                    pressedPhotoView = view as PhotoView
                    showImageMenu()
                    true
                }
                load(images[position])
            }
            container.addView(photoView)
            return photoView
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }
    }
}
