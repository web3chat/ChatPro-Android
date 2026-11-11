package com.fzm.chat.square

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.utils.defaultAvatar
import com.fzm.chat.biz.utils.onExplain
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.ActivitySquareOfficialBinding
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
import org.koin.android.ext.android.inject

/**
 * 个人信息 / 我的公众号（广场右上角头像入口进入）
 *
 * - 个人（未申请 / 审核中 / 被拒）：只展示个人信息（头像 + 昵称 + 地址 + 我的关注/粉丝/帖子流量）；
 *   未申请 / 被拒时底部有「申请公众号 / 重新申请」按钮，跳到 [SquareOfficialApplyActivity] 填表
 * - 已认证：展示公众号资料（头像 + 昵称，可修改），对应 PUT /square/app/official
 * - 已封禁：仅展示状态
 */
class SquareOfficialActivity : BizActivity() {

    companion object {
        private const val MAX_NAME = 32
    }

    private val binding by init { ActivitySquareOfficialBinding.inflate(layoutInflater) }

    private val ossService by route<OssService>(OssModule.APP_OSS)

    private val delegate by inject<LoginDelegate>()

    private val contactManager by inject<ContactManager>()

    private var state: String = SquareOfficialMe.NONE

    /** 上次申请的名称（被拒后重新申请回填） */
    private var lastApplyName: String? = null

    /** 是否从申请公众号页返回（返回后刷新状态，不打扰当前页面的头像选择等操作） */
    private var refreshOnResume = false

    /** 是否已渲染过状态（首次加载失败时按「未申请」兜底；刷新失败不覆盖已有状态） */
    private var rendered = false

    /** 当前公众号头像（用于回显，空串表示无头像） */
    private var avatarUrl: String = ""

    /** 已上传但尚未保存的头像（"" 表示清空头像） */
    private var pendingAvatar: String? = null

    private var submitting = false

    override val root: View
        get() = binding.root

    override fun initView() {

    }

    override fun initData() {
        // 昵称/头像来自本地用户信息，加载完成后刷新个人卡片
        delegate.current.observe(this) {
            if (binding.llSelf.isVisible) renderSelf()
        }
        loadState()
        loadMineCounts()
    }

    override fun onResume() {
        super.onResume()
        // 从关注/粉丝/帖子页返回后数字可能变化
        loadMineCounts()
        // 从申请公众号页返回后刷新状态（提交成功变「审核中」/ 重新申请被拒仍然为「未通过」）
        if (refreshOnResume) {
            refreshOnResume = false
            loadState()
        }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.btnApply.setOnClickListener {
            refreshOnResume = true
            SquareOfficialApplyActivity.start(this, lastApplyName)
        }
        binding.btnSaveProfile.setOnClickListener { saveProfile() }
        binding.ivProfileAvatar.setOnClickListener { pickAvatar() }
        binding.tvProfileAvatarClear.setOnClickListener { clearAvatar() }
        binding.llMyFollows.setOnClickListener {
            startActivity(SquareFollowActivity.intent(this, SquareFollowActivity.TYPE_FOLLOWING))
        }
        binding.llMyFollowers.setOnClickListener {
            startActivity(SquareFollowActivity.intent(this, SquareFollowActivity.TYPE_FOLLOWERS))
        }
        binding.llMyPosts.setOnClickListener {
            startActivity(SquareMyPostsActivity.intent(this))
        }
    }

    // ==================== 我的关注 / 粉丝 / 帖子流量 ====================

    /**
     * 入口右侧的数量（关注 / 粉丝 / 帖子）
     *
     * 只取第一页的 total，失败保持空白（不打扰用户）
     */
    private fun loadMineCounts() {
        lifecycleScope.launch {
            val follows = SquareApi.follows(1, 1)
            if (follows.isSuccess) {
                binding.tvFollowCount.text = (follows.data?.totalNum ?: 0).toString()
            }
            val followers = SquareApi.followers(1, 1)
            if (followers.isSuccess) {
                binding.tvFollowerCount.text = (followers.data?.totalNum ?: 0).toString()
            }
            val posts = SquareApi.mePosts(1, 1)
            if (posts.isSuccess) {
                binding.tvPostCount.text = (posts.data?.postNum ?: 0).toString()
            }
        }
    }

    // ==================== 状态 ====================

    private fun loadState() {
        lifecycleScope.launch {
            val resp = SquareApi.officialMe()
            if (resp.isSuccess) {
                // official/me 带广场开关（square 字段，与 GET /status 同结构），同步到内存状态
                SquareGate.update(resp.data?.square)
                render(resp.data)
            } else {
                if (!resp.isTransientError()) {
                    // 偶发错误（服务端内部错误/网络异常）不提示，页面保持已有状态
                    toast(squareMessage(resp))
                }
                // 首次加载失败：按「未申请」兜底，保证个人信息与申请入口可用
                if (!rendered) render(null)
            }
        }
    }

    private fun render(me: SquareOfficialMe?) {
        state = me?.currentState ?: SquareOfficialMe.NONE
        rendered = true
        // 缓存本人公众号头像，广场官方帖优先展示
        SquareAvatarCache.cacheOfficial(me, delegate.getAddress())
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

        val canApply = state == SquareOfficialMe.NONE || state == SquareOfficialMe.REJECTED
        val showProfile = state == SquareOfficialMe.APPROVED
        // 个人用户（未开号 / 审核中 / 被拒）展示个人信息卡
        val showSelf = !showProfile && state != SquareOfficialMe.BANNED
        // 未申请：不显示状态卡（「申请通过后…」提示与申请页重复，未通过/审核中/已认证仍需展示）
        binding.llState.isVisible = state != SquareOfficialMe.NONE
        // 申请入口：未申请 → 申请公众号；被拒 → 重新申请（表单在新页面）
        binding.btnApply.isVisible = canApply
        if (canApply) {
            binding.btnApply.setText(
                if (state == SquareOfficialMe.REJECTED) R.string.square_official_reapply
                else R.string.square_official_apply
            )
        }
        lastApplyName = me?.apply?.name
        binding.llProfile.isVisible = showProfile
        binding.llSelf.isVisible = showSelf
        binding.ctbTitle.setTitle(
            getString(
                if (showProfile || state == SquareOfficialMe.BANNED) {
                    R.string.square_my_official
                } else {
                    R.string.square_my_profile
                }
            )
        )
        if (showSelf) renderSelf()

        if (showProfile) {
            renderProfile(me)
        }
    }

    /**
     * 个人信息（个人用户）：头像 + 昵称 + 地址
     */
    private fun renderSelf() {
        val address = delegate.getAddress().orEmpty()
        binding.tvSelfName.text = delegate.current.value?.nickname?.takeIf { it.isNotBlank() }
            ?: shortAddress(address)
        binding.tvSelfAddress.text = shortAddress(address)
        val avatar = binding.ivSelfAvatar
        avatar.load(
            SquareAvatarCache.getForAddress(address, false),
            defaultAvatar(ChatConst.PRIVATE_CHANNEL)
        )
        // 链上头像解析完成后刷新（已请求过的地址不会重复查询）
        resolveSquareAvatar(lifecycleScope, contactManager, address) {
            avatar.load(
                SquareAvatarCache.getForAddress(address, false),
                defaultAvatar(ChatConst.PRIVATE_CHANNEL)
            )
        }
    }

    /**
     * 地址脱敏展示：1CJ5****vJqr
     */
    private fun shortAddress(address: String): String {
        if (address.length <= 12) return address
        return address.take(6) + "****" + address.takeLast(4)
    }

    /**
     * 已认证：回显公众号资料
     */
    private fun renderProfile(me: SquareOfficialMe?) {
        pendingAvatar = null
        avatarUrl = me?.official?.avatar.orEmpty()
        binding.etProfileName.setText(me?.official?.name.orEmpty())
        binding.tvProfileAvatarHint.text = getString(R.string.square_official_avatar_change)
        binding.tvProfileAvatarClear.isVisible = avatarUrl.isNotEmpty()
        if (avatarUrl.isNotEmpty()) {
            binding.ivProfileAvatar.load(avatarUrl)
        } else {
            binding.ivProfileAvatar.setImageDrawable(null)
        }
    }

    // ==================== 头像（公众号资料） ====================

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
            pendingAvatar = url
            binding.ivProfileAvatar.load(url)
            binding.tvProfileAvatarHint.text = getString(R.string.square_official_avatar_set)
            binding.tvProfileAvatarClear.isVisible = true
        }
    }

    // ==================== 公众号资料（已认证） ====================

    private fun clearAvatar() {
        pendingAvatar = ""
        avatarUrl = ""
        binding.ivProfileAvatar.setImageDrawable(null)
        binding.tvProfileAvatarClear.isVisible = false
        binding.tvProfileAvatarHint.text = getString(R.string.square_official_avatar_change)
    }

    private fun saveProfile() {
        if (submitting) return
        val name = binding.etProfileName.text.toString().trim().take(MAX_NAME)
        if (name.isEmpty()) {
            toast(R.string.square_official_name_empty)
            return
        }
        // 未动头像则不传（null）；清了头像传空串；换新头像传新地址
        val avatar = pendingAvatar ?: avatarUrl.takeIf { it.isNotEmpty() }
        submitting = true
        lifecycleScope.launch {
            val resp = SquareApi.updateOfficial(name, avatar)
            submitting = false
            if (resp.isSuccess) {
                resp.data?.let { profile ->
                    val address = profile.address?.takeIf { it.isNotBlank() }
                        ?: delegate.getAddress().orEmpty()
                    SquareAvatarCache.putOfficial(address, profile.avatar.orEmpty())
                }
                toast(R.string.square_official_updated)
                loadState()
            } else {
                toast(squareMessage(resp))
            }
        }
    }
}
