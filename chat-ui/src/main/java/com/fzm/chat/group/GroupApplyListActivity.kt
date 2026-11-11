package com.fzm.chat.group

import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.bean.Contact
import com.fzm.chat.core.data.bean.GroupApplyTO
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.databinding.ActivityGroupApplyListBinding
import com.fzm.chat.router.main.MainModule
import com.zjy.architecture.ext.format
import com.zjy.architecture.ext.gone
import com.zjy.architecture.ext.load
import com.zjy.architecture.ext.toast
import com.zjy.architecture.ext.visible
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2026/09/21
 * Description: 加群申请列表（群主/管理员审批"群主审批"模式的加群申请）
 */
@Route(path = MainModule.GROUP_APPLY_LIST)
class GroupApplyListActivity : BizActivity() {

    @JvmField
    @Autowired
    var groupId: Long = 0L

    private val viewModel by viewModel<GroupViewModel>()
    private val contactManager by inject<ContactManager>()

    private lateinit var mAdapter: BaseQuickAdapter<ApplyItem, BaseViewHolder>

    private val binding by init { ActivityGroupApplyListBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
        binding.rvApply.layoutManager = LinearLayoutManager(this)
        mAdapter = object : BaseQuickAdapter<ApplyItem, BaseViewHolder>(
            R.layout.item_group_apply, null
        ) {
            override fun convert(holder: BaseViewHolder, item: ApplyItem) {
                holder.getView<ChatAvatarView>(R.id.iv_avatar)
                    .load(item.user.getDisplayImage(), R.mipmap.default_avatar_round)
                holder.setText(R.id.tv_name, item.user.getDisplayName())
                holder.setText(
                    R.id.tv_time,
                    if (item.apply.applyTime > 0)
                        item.apply.applyTime.format("yyyy/MM/dd HH:mm")
                    else ""
                )
                val note = item.apply.applyNote.orEmpty()
                holder.setGone(R.id.tv_note, note.isEmpty())
                holder.setText(R.id.tv_note, note)
                holder.getView<View>(R.id.tv_accept).setOnClickListener {
                    viewModel.acceptGroupApply(groupId, item.apply.applyRecordId)
                }
                holder.getView<View>(R.id.tv_reject).setOnClickListener {
                    viewModel.rejectGroupApply(groupId, item.apply.applyRecordId)
                }
            }
        }
        binding.rvApply.adapter = mAdapter
    }

    override fun initData() {
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.groupApplyListResult.observe(this) { list ->
            lifecycleScope.launch {
                // 文档：需要待审列表时拉回后只展示 applyStatus=0
                val waiting = list.filter { it.isWaiting }
                val items = waiting.map { apply ->
                    ApplyItem(apply, contactManager.getUserInfo(apply.applyMemberId, false))
                }
                mAdapter.setList(items)
                if (items.isEmpty()) {
                    binding.tvEmpty.visible()
                    binding.rvApply.gone()
                } else {
                    binding.tvEmpty.gone()
                    binding.rvApply.visible()
                }
            }
        }
        viewModel.applyAcceptResult.observe(this) {
            toast(R.string.chat_tips_apply_accepted)
            viewModel.getGroupApplys(groupId)
        }
        viewModel.applyRejectResult.observe(this) {
            toast(R.string.chat_tips_apply_rejected)
            viewModel.getGroupApplys(groupId)
        }
        viewModel.getGroupApplys(groupId)
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
    }

    override fun onBackPressed() {
        setResult(RESULT_OK)
        super.onBackPressed()
    }

    /**
     * 列表项：申请 + 申请人本地资料
     */
    private data class ApplyItem(val apply: GroupApplyTO, val user: Contact)
}
