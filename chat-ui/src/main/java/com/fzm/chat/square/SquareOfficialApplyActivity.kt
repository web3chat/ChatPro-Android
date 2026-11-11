package com.fzm.chat.square

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.onExplain
import com.fzm.chat.databinding.ActivitySquareApplyBinding
import com.fzm.chat.router.oss.MediaType
import com.fzm.chat.router.oss.OssModule
import com.fzm.chat.router.oss.OssService
import com.fzm.chat.router.route
import com.fzm.chat.utils.GlideEngine
import com.luck.picture.lib.PictureSelector
import com.luck.picture.lib.config.PictureMimeType
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.listener.OnResultCallbackListener
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.isContent
import kotlinx.coroutines.launch

/**
 * 申请公众号：整页表单（头像选填 + 名称必填 + 简介选填）
 *
 * 入口：个人信息页（[SquareOfficialActivity]）在「未申请 / 未通过」状态下的
 * 「申请公众号 / 重新申请」按钮。
 *
 * 顶部状态卡与被拒原因来自 GET /square/app/official/me；提交成功后直接返回，
 * 个人信息页 onResume 会刷新状态（变为审核中）。
 *
 * 同一地址同时只能有一条待审申请，每天最多 3 次。
 */
class SquareOfficialApplyActivity : BizActivity() {

    companion object {
        private const val EXTRA_NAME = "applyName"

        /** 名称 / 简介 最大字数（与服务端一致） */
        private const val MAX_NAME = 32
        private const val MAX_INTRO = 200

        /**
         * @param prefillName 上一次申请的名称（被拒后重新申请回填，可空）
         */
        fun start(context: Context, prefillName: String?) {
            context.startActivity(
                Intent(context, SquareOfficialApplyActivity::class.java)
                    .putExtra(EXTRA_NAME, prefillName)
            )
        }
    }

    private val binding by init { ActivitySquareApplyBinding.inflate(layoutInflater) }

    private val ossService by route<OssService>(OssModule.APP_OSS)

    /** 已上传的头像（空串表示未设置） */
    private var applyAvatarUrl = ""

    /** 当前状态是否可提交申请（未申请 / 未通过） */
    private var canApply = true

    private var submitting = false

    override val root: View
        get() = binding.root

    override fun initView() {
        // 先回填上次填写的名称，状态接口回来后以「输入框为空才回填」兜底
        binding.etName.setText(intent?.getStringExtra(EXTRA_NAME).orEmpty())
    }

    override fun initData() {
        loadState()
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.ivFormAvatar.setOnClickListener { pickAvatar() }
        binding.btnSubmit.setOnClickListener { submit() }
    }

    /**
     * 状态卡：未申请 / 未通过（附原因）
     *
     * 状态若已变成审核中 / 已认证 / 已封禁（运营台在此期间改动），
     * 展示对应提示并置灰提交按钮，避免重复提交。
     */
    private fun loadState() {
        lifecycleScope.launch {
            val resp = SquareApi.officialMe()
            if (resp.isSuccess) {
                render(resp.data)
            } else if (!resp.isTransientError()) {
                toast(squareMessage(resp))
            }
        }
    }

    private fun render(me: SquareOfficialMe?) {
        val state = me?.currentState ?: SquareOfficialMe.NONE
        canApply = state == SquareOfficialMe.NONE || state == SquareOfficialMe.REJECTED
        binding.tvState.text = getString(
            when (state) {
                SquareOfficialMe.PENDING -> R.string.square_official_state_pending
                SquareOfficialMe.APPROVED -> R.string.square_official_state_approved
                SquareOfficialMe.REJECTED -> R.string.square_official_state_rejected
                SquareOfficialMe.BANNED -> R.string.square_official_state_banned
                else -> R.string.square_official_state_none
            }
        )
        binding.tvStateTip.text = when (state) {
            SquareOfficialMe.PENDING -> getString(R.string.square_official_tip_pending)

            SquareOfficialMe.APPROVED -> getString(
                R.string.square_official_tip_approved,
                me?.official?.name ?: ""
            )

            SquareOfficialMe.REJECTED -> buildString {
                append(getString(R.string.square_official_tip_rejected))
                me?.apply?.remark?.takeIf { it.isNotBlank() }?.let {
                    append(getString(R.string.square_official_tip_reason, it))
                }
            }

            SquareOfficialMe.BANNED -> getString(R.string.square_official_tip_banned)
            else -> getString(R.string.square_official_tip_none)
        }
        binding.btnSubmit.isEnabled = canApply
        binding.btnSubmit.alpha = if (canApply) 1f else 0.5f

        // 被拒后重新申请：回填上一次申请的名称
        me?.apply?.name?.takeIf { it.isNotBlank() }?.let {
            if (binding.etName.text.isNullOrBlank()) {
                binding.etName.setText(it)
            }
        }
    }

    // ==================== 头像（选填） ====================

    private fun pickAvatar() {
        PermissionX.init(this)
            .permissions(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            .onExplain(this)
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    toast(com.fzm.chat.biz.R.string.biz_permission_not_granted)
                    return@request
                }
                PictureSelector.create(this)
                    .openGallery(PictureMimeType.ofImage())
                    .maxSelectNum(1)
                    .isCompress(true)
                    .isCamera(false)
                    .theme(R.style.chat_picture_style)
                    .imageEngine(GlideEngine.createGlideEngine())
                    .forResult(object : OnResultCallbackListener<LocalMedia> {
                        override fun onResult(result: MutableList<LocalMedia>?) {
                            val path = result?.firstOrNull()?.path
                            if (path.isNullOrBlank()) return
                            uploadAvatar(path)
                        }

                        override fun onCancel() {
                        }
                    })
            }
    }

    private fun uploadAvatar(path: String) {
        lifecycleScope.launch {
            val url = try {
                if (path.isContent()) {
                    ossService?.uploadMedia(null, Uri.parse(path), MediaType.PICTURE, false) ?: ""
                } else {
                    ossService?.uploadMedia(null, path, MediaType.PICTURE, false) ?: ""
                }
            } catch (e: Exception) {
                ""
            }
            if (url.isEmpty()) {
                toast(R.string.square_upload_failed)
                return@launch
            }
            applyAvatarUrl = url
            binding.ivFormAvatar.load(url)
            binding.tvFormAvatarHint.text = getString(R.string.square_official_avatar_set)
        }
    }

    // ==================== 提交申请 ====================

    private fun submit() {
        if (submitting || !canApply) return
        val name = binding.etName.text.toString().trim().take(MAX_NAME)
        if (name.isEmpty()) {
            toast(R.string.square_official_name_empty)
            return
        }
        val intro = binding.etIntro.text.toString().trim().take(MAX_INTRO)
        submitting = true
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.alpha = 0.6f
        lifecycleScope.launch {
            val resp = SquareApi.applyOfficial(
                name,
                applyAvatarUrl.takeIf { it.isNotEmpty() },
                intro
            )
            if (resp.isSuccess) {
                toast(R.string.square_official_applied)
                finish()
            } else {
                toast(squareMessage(resp))
                submitting = false
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.alpha = 1f
            }
        }
    }
}
