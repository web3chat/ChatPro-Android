package com.fzm.chat.group

import android.view.View
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.GroupInfoTO
import com.fzm.chat.core.data.po.GroupInfo
import com.fzm.chat.databinding.ActivityJoinGroupInfoBinding
import com.fzm.chat.router.main.MainModule
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.visible
import org.koin.androidx.viewmodel.ext.android.viewModel

@Route(path = MainModule.JOIN_GROUP_INFO)
class JoinGroupInfoActivity : BizActivity(), View.OnClickListener {

    @JvmField
    @Autowired
    var groupId: Long = 0L

    @JvmField
    @Autowired
    var server: String? = null

    @JvmField
    @Autowired
    var inviterId: String? = null

    private var groupInfo: GroupInfoTO? = null

    /** 提交申请后需要复查群信息（判断是否已进群 / 仍是待审批） */
    private var pendingApplyCheck = false

    private val viewModel by viewModel<GroupViewModel>()

    private val binding by init { ActivityJoinGroupInfoBinding.inflate(layoutInflater) }

    override val darkStatusColor: Boolean = true

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.groupPubResult.observe(this) {
            if (pendingApplyCheck) {
                // 申请/回退 join-group 之后复查：已进群就直接进会话，否则按"待审批"处理
                pendingApplyCheck = false
                if (it.person != null) {
                    toGroupChat()
                    return@observe
                }
                toast(R.string.chat_tips_apply_join_submitted)
                binding.btnJoinGroup.isEnabled = false
                binding.btnJoinGroup.setText(R.string.chat_action_apply_join_pending)
                return@observe
            }
            setupGroupInfo(it)
        }
        viewModel.groupJoinResult.observe(this) {
            toGroupChat()
        }
        // 申请提交成功后复查群信息，兼容服务端未部署申请接口、退回 join-group 的情况
        viewModel.createApplyResult.observe(this) {
            pendingApplyCheck = true
            viewModel.getGroupPubInfo(groupId, server)
        }
    }

    override fun initData() {
        viewModel.getGroupPubInfo(groupId, server)
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.btnJoinGroup.setOnClickListener(this)
    }

    private fun setupGroupInfo(info: GroupInfoTO) {
        groupInfo = info
        binding.ivAvatar.visible()
        binding.btnJoinGroup.visible()
        binding.ivAvatar.load(info.avatar, R.mipmap.default_avatar_room)
        binding.tvGroupName.text = info.publicName
        binding.tvMarkId.text = getString(R.string.chat_tips_group_mark_id, info.markId)
        binding.tvMemberNum.text =
            getString(R.string.chat_tips_join_member_number, info.memberNum)
        when {
            info.person != null || info.joinType == GroupInfo.CAN_JOIN_GROUP -> {
                // 已在群内或无需审批，直接进入
                binding.btnJoinGroup.isEnabled = true
                binding.btnJoinGroup.setText(R.string.chat_action_join_group)
            }

            info.joinType == GroupInfo.NEED_APPROVE_GROUP -> {
                // 需群主/管理员审批，提交入群申请
                binding.btnJoinGroup.isEnabled = true
                binding.btnJoinGroup.setText(R.string.chat_action_apply_join_group)
            }

            else -> {
                binding.btnJoinGroup.isEnabled = false
                binding.btnJoinGroup.setText(R.string.chat_action_can_not_join)
            }
        }
    }

    private fun toGroupChat() {
        ARouter.getInstance().build(MainModule.CHAT)
            .withString("address", "$groupId")
            .withInt("channelType", ChatConst.GROUP_CHANNEL)
            .navigation()
        finish()
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_join_group -> {
                // 加入群聊
                groupInfo?.let {
                    when {
                        it.person != null -> toGroupChat()
                        it.joinType == GroupInfo.NEED_APPROVE_GROUP ->
                            // 优先调用入群申请接口；服务端未部署（404）时自动退回 join-group
                            viewModel.applyJoinGroup(groupId, server, inviterId)

                        else -> viewModel.joinGroup(groupId, server, inviterId)
                    }
                }
            }
        }
    }
}