package com.fzm.chat.redpacket.ui

import android.content.Intent
import android.view.View
import android.widget.CheckBox
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.widget.ChatAvatarView
import com.fzm.chat.core.data.model.GroupUser
import com.fzm.chat.redpacket.R
import com.fzm.chat.redpacket.databinding.ActivityRedPacketReceiverBinding
import com.zjy.architecture.ext.load
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 红包「指定领取人」选择页
 *
 * 群成员多选，可按昵称或地址搜索（展示方式与「我的」页面头像下方的昵称/地址一致）；
 * 选中的成员会作为合约白名单，只有他们可以领取该红包。
 */
class RedPacketReceiverActivity : BizActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_ADDRESSES = "addresses"
        const val EXTRA_NAMES = "names"
    }

    private val binding by init<ActivityRedPacketReceiverBinding>()

    private val viewModel by viewModel<RedPacketViewModel>()

    private var groupId = 0L

    private val members = mutableListOf<GroupUser>()

    /** 已选成员的聊天地址 */
    private val selected = mutableSetOf<String>()

    private lateinit var mAdapter: BaseQuickAdapter<GroupUser, BaseViewHolder>

    override val root: View
        get() = binding.root

    override fun initView() {
        groupId = intent?.getLongExtra(EXTRA_GROUP_ID, 0L) ?: 0L
        intent?.getStringArrayListExtra(EXTRA_SELECTED)?.forEach { selected.add(it) }

        mAdapter = object : BaseQuickAdapter<GroupUser, BaseViewHolder>(
            R.layout.item_red_packet_receiver,
            mutableListOf()
        ) {
            override fun convert(holder: BaseViewHolder, item: GroupUser) {
                holder.setText(R.id.tv_name, item.getDisplayName())
                holder.setText(R.id.tv_address, item.address)
                holder.getView<CheckBox>(R.id.cb_select).isChecked = selected.contains(item.address)
                holder.getView<ChatAvatarView>(R.id.iv_avatar)
                    .load(item.getDisplayImage(), R.mipmap.default_avatar_round)
                holder.getView<View>(R.id.ll_item).setOnClickListener {
                    toggleSelect(item)
                }
            }
        }
        binding.rvMembers.layoutManager = LinearLayoutManager(this)
        binding.rvMembers.adapter = mAdapter
        updateSelectedCount()
    }

    override fun initData() {
        if (groupId <= 0) return
        viewModel.fetchGroupUsers(groupId).observe(this) { list ->
            val self = viewModel.delegate.getAddress()
            members.clear()
            members.addAll(list.filter { it.address != self })
            applyFilter(binding.etSearch.text?.toString())
        }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressed() }
        binding.ctbTitle.setOnRightClickListener { confirm() }
        binding.etSearch.addTextChangedListener { applyFilter(it?.toString()) }
    }

    /**
     * 按昵称或地址过滤群成员
     */
    private fun applyFilter(keyword: String?) {
        val key = keyword?.trim().orEmpty()
        val list = if (key.isEmpty()) {
            members.toList()
        } else {
            members.filter {
                it.getDisplayName().contains(key, ignoreCase = true) ||
                    it.address.contains(key, ignoreCase = true)
            }
        }
        mAdapter.setNewData(list.toMutableList())
        binding.tvEmpty.isVisible = list.isEmpty()
    }

    private fun toggleSelect(user: GroupUser) {
        if (!selected.add(user.address)) {
            selected.remove(user.address)
        }
        val index = mAdapter.data.indexOfFirst { it.address == user.address }
        if (index >= 0) mAdapter.notifyItemChanged(index)
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        binding.tvSelectedTips.text = getString(R.string.red_packet_receivers_count, selected.size)
    }

    /**
     * 返回选中的成员（聊天地址 + 昵称）
     */
    private fun confirm() {
        val addresses = ArrayList<String>()
        val names = ArrayList<String>()
        members.forEach {
            if (selected.contains(it.address)) {
                addresses.add(it.address)
                names.add(it.getDisplayName())
            }
        }
        setResult(
            RESULT_OK,
            Intent()
                .putStringArrayListExtra(EXTRA_ADDRESSES, addresses)
                .putStringArrayListExtra(EXTRA_NAMES, names)
        )
        finish()
    }
}
