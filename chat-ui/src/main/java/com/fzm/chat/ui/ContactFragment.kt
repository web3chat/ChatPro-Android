package com.fzm.chat.ui

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.biz.widget.FadePageTransformer
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.base.FunctionModules
import com.fzm.chat.biz.base.enableOA
import com.fzm.chat.contact.ContactListFragment
import com.fzm.chat.contact.ContactViewModel
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.FragmentContactBinding
import com.fzm.chat.group.GroupFragment
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.oa.OAModule
import com.fzm.chat.router.oa.OAService
import com.fzm.chat.router.oa.hasCompany
import com.fzm.chat.router.oa.isAdmin
import com.fzm.chat.router.route
import com.fzm.chat.utils.addrIsContained
import com.fzm.chat.widget.HomeActionPopup
import com.fzm.widget.ScrollPagerAdapter
import com.zjy.architecture.ext.*
import com.zjy.architecture.util.other.BarUtils
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2020/12/18
 * Description:
 */
@Route(path = MainModule.CONTACT)
class ContactFragment : BizFragment(), View.OnClickListener {

    private val viewModel by viewModel<ContactViewModel>()
    private val binding by init<FragmentContactBinding>()
    private val oaService by route<OAService>(OAModule.SERVICE)
    private val delegate by inject<LoginDelegate>()

    /** 是否已完成首次加载（首次带加载框，之后每次切回都是静默刷新） */
    private var contactLoaded = false

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {
        binding.rlTitle.layoutParams = binding.rlTitle.layoutParams.apply {
            height = height + BarUtils.getStatusBarHeight(requireContext())
        }
        BarUtils.addPaddingTopEqualStatusBarHeight(requireContext(), binding.rlTitle)
        binding.vpContact.apply {
            val friend = ContactListFragment()
            val group = GroupFragment()
            adapter = ScrollPagerAdapter(
                childFragmentManager,
                listOf("好友", "群聊"),
                listOf(friend, group)
            )
            setPageTransformer(false, FadePageTransformer())
            offscreenPageLimit = 2
        }
        binding.vpContact.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {

            }

            override fun onPageSelected(position: Int) {
                if (position == 0) {
                    binding.tvFriend.setTextColor(resources.getColor(R.color.biz_text_grey_dark))
                    binding.tvFriend.textSize = 17f
                    binding.tvGroup.setTextColor(resources.getColor(R.color.biz_text_grey_light))
                    binding.tvGroup.textSize = 14f
                } else if (position == 1) {
                    binding.tvFriend.setTextColor(resources.getColor(R.color.biz_text_grey_light))
                    binding.tvFriend.textSize = 14f
                    binding.tvGroup.setTextColor(resources.getColor(R.color.biz_text_grey_dark))
                    binding.tvGroup.textSize = 17f
                }
            }

            override fun onPageScrollStateChanged(state: Int) {

            }
        })
        // 好友/黑名单的请求放到 onResume（页面真正可见时），见 updateContact
    }

    private fun updateContact(showLoading: Boolean) {
        // 获取好友和黑名单信息：首次进入展示加载框，再次切回时静默刷新
        viewModel.getFriendList(showLoading)
        viewModel.getBlockList(showLoading)
    }

    override fun onResume() {
        super.onResume()
        // 每次切到前台都请求接口（未展示在前台时不会请求）：首次进入带加载框，之后静默刷新
        updateContact(showLoading = !contactLoaded)
        contactLoaded = true
        if (FunctionModules.enableOA) {
            lifecycleScope.launch {
                oaService?.getCompanyUser(null)
            }
        }
    }

    override fun initData() {
        viewModel.companyUser.observe(viewLifecycleOwner) { user ->
            if (user.hasCompany) {
                binding.oaHeader.root.visible()
                binding.oaHeader.epName.text = user.company?.name
                binding.oaHeader.epCode.text = user.company?.id
                binding.oaHeader.ivEpAvatar.load(user.company?.avatar, R.mipmap.default_avatar_ep)
                binding.oaHeader.tvManage.setVisible(user.isAdmin)
                binding.oaHeader.tvManage.setOnClickListener {
                    oaService?.openCompanyManagement(requireActivity())
                }
                binding.oaHeader.rlTeamTree.setOnClickListener {
                    oaService?.openCompanyUserList(requireActivity())
                }
            } else {
                binding.oaHeader.root.gone()
            }
        }
    }

    override fun setEvent() {
        binding.tvFriend.setOnClickListener(this)
        binding.tvGroup.setOnClickListener(this)
        binding.ivScan.setOnClickListener(this)
        binding.ivSearch.setOnClickListener(this)
        binding.ivAdd.setOnClickListener(this)
        binding.tvBlackList.setOnClickListener(this)
//        binding.blackList.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.tv_friend -> binding.vpContact.currentItem = 0
            R.id.tv_group -> binding.vpContact.currentItem = 1
            R.id.iv_scan -> {
                v.checkSingle { ARouter.getInstance().build(MainModule.QR_SCAN).navigation() }
            }

            R.id.iv_search -> {
                ARouter.getInstance().build(MainModule.SEARCH_LOCAL).navigation()
            }

            R.id.tv_black_list -> {
                ARouter.getInstance().build(MainModule.BLOCK_LIST_ATY).navigation()
            }

            R.id.iv_add -> {
                HomeActionPopup.create(requireContext(), this).show(binding.ivAdd)
            }
            // 弹窗中的按钮
            R.id.scan -> {
                v.checkSingle(1000) { ARouter.getInstance().build(MainModule.QR_SCAN).navigation() }
            }

            R.id.create_group -> {
                if (W.appType == WConfig.APP_CIP) {
                    delegate.getAddress()?.let { addr ->
                        if (addrIsContained(addr)) {
                            ARouter.getInstance().build(MainModule.CREATE_INVITE_GROUP).navigation()
                        } else {
                            toast("暂无创建群聊权限")
                        }
                    }
                } else {
                    ARouter.getInstance().build(MainModule.CREATE_INVITE_GROUP).navigation()
                }

            }

            R.id.join -> {
                ARouter.getInstance().build(MainModule.SEARCH_ONLINE).navigation()
            }

            R.id.qr_code -> {
                ARouter.getInstance().build(MainModule.QR_CODE).navigation()
            }

            R.id.create_company -> {
                oaService?.openCreateCompanyPage()
            }

            R.id.okr -> {
                oaService?.openOKRPage(null)
            }
        }
    }
}