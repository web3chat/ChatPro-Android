package com.fzm.chat.contact

import android.os.Bundle
import android.view.View
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.databinding.LayoutEmptyFriendsListBinding
import com.fzm.chat.router.main.MainModule

/**
 * @author zhengjy
 * @since 2020/12/18
 * Description:
 */
@Route(path = MainModule.CONTACT_LIST)
class ContactListFragment : UserListFragment() {

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        mAdapter.addChildClickViewIds(R.id.ll_item)
        mAdapter.setOnItemChildClickListener { _, _, position ->
            ARouter.getInstance().build(MainModule.CONTACT_INFO)
                .withString("address", users[position].address)
                .navigation()
        }
        val bind = LayoutEmptyFriendsListBinding.inflate(layoutInflater)
        bind.tvInviteFriends.setOnClickListener {
            ARouter.getInstance().build(MainModule.QR_CODE).navigation()
        }
        mAdapter.headerWithEmptyEnable = true
        mAdapter.setEmptyView(bind.root)
    }

    override fun initData() {
        super.initData()
        viewModel.friendList.observe(viewLifecycleOwner) {
            mAdapter.setList(it.sortedWith(comparator))
        }
        // 好友列表由外层「通讯录」Tab 在页面可见时统一拉取（见 ContactFragment.updateContact），
        // 这里不再在 Fragment 创建时发起请求，避免 Tab 未展示时请求接口
    }

    override fun loadUserList() {
        viewModel.getFriendList()
    }
}