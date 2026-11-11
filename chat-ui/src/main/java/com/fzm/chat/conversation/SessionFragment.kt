package com.fzm.chat.conversation

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
import com.fzm.chat.biz.base.*
import com.fzm.chat.biz.bus.BusEvent
import com.fzm.chat.biz.bus.LiveDataBus
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.databinding.FragmentSessionBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.oa.OAModule
import com.fzm.chat.router.oa.OAService
import com.fzm.chat.router.route
import com.fzm.chat.square.SquareApi
import com.fzm.chat.square.SquareFragment
import com.fzm.chat.square.SquareGate
import com.fzm.chat.square.SquareUnread
import com.fzm.chat.utils.addrIsContained
import com.fzm.chat.widget.HomeActionPopup
import com.fzm.widget.ScrollPagerAdapter
import com.zjy.architecture.ext.*
import com.zjy.architecture.util.other.BarUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * @author zhengjy
 * @since 2020/12/09
 * Description:
 */
@Route(path = MainModule.SESSION)
class SessionFragment : BizFragment(), View.OnClickListener {

    companion object {
        /** 顶部 tab 顺序：0 广场 / 1 私聊 / 2 群聊（角标下标同此） */
        private const val TAB_SQUARE = 0
        private const val TAB_PRIVATE = 1
        private const val TAB_GROUP = 2

        /** 广场未读（新帖）轮询间隔：60s */
        private const val SQUARE_CHECK_INTERVAL = 60_000L

        /** 检查广场新帖时拉取第 1 页的条数（与广场列表页一致） */
        private const val SQUARE_CHECK_PAGE_SIZE = 20
    }

    private val binding by init<FragmentSessionBinding>()

    private lateinit var person: SessionListFragment
    private lateinit var group: SessionListFragment
    private lateinit var square: SquareFragment

    private var privateUnread = 0
    private var groupUnread = 0

    /** 广场未读检查是否正在进行（防重入） */
    private var checkingSquareUnread = false

    /** 广场未读轮询任务（页面进入后台时取消） */
    private var squareUnreadJob: Job? = null

    private val delegate by inject<LoginDelegate>()
    private val oaService by route<OAService>(OAModule.SERVICE)

    override val root: View
        get() = binding.root

    override fun initView(view: View, savedInstanceState: Bundle?) {
        BarUtils.addPaddingTopEqualStatusBarHeight(requireContext(), binding.root)
        binding.vpMessage.apply {
            setCanScroll(true)
            square = SquareFragment()
            person = SessionListFragment.create(ChatConst.PRIVATE_CHANNEL)
            group = SessionListFragment.create(ChatConst.GROUP_CHANNEL)
            adapter = ScrollPagerAdapter(
                childFragmentManager,
                listOf(
                    getString(R.string.chat_title_square),
                    getString(R.string.chat_title_private_chat),
                    getString(R.string.chat_title_group_chat)
                ),
                listOf(square, person, group)
            )
            setPageTransformer(false, FadePageTransformer())
            offscreenPageLimit = 2
        }
        binding.tabLayout.setViewPager(binding.vpMessage)
        for (i in 0 until binding.tabLayout.tabCount) {
            binding.tabLayout.getMsgView(i).apply {
                strokeWidth = 0
                backgroundColor = resources.getColor(R.color.biz_red_tips)
            }
        }
        // 广场 tab 未读角标：进入广场立即清零（基准 = 当前列表里最新的帖子 id）
        binding.vpMessage.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (position == TAB_SQUARE) {
                    val ctx = context?.applicationContext ?: return
                    SquareUnread.markSeen(ctx, square.newestPostId())
                    showSquareUnread(0)
                }
            }
        })
        // 广场列表刷新完成：正在看广场时把新帖记为已读
        square.onFeedLoaded = { newestId -> onSquareFeedLoaded(newestId) }
        LiveDataBus.of(BusEvent::class.java).changeTab().observe(this) {
            if (it.tab == 0) {
                binding.vpMessage.currentItem = it.subTab
            }
        }
    }

    override fun initData() {
        person.setSessionsChangedListener { sessions ->
            privateUnread = sessions.filter {
                it.getType() == ChatConst.PRIVATE_CHANNEL && !it.isNoDisturb()
            }.sumOf {
                it.unreadNum()
            }
            LiveDataBus.of(BusEvent::class.java).unreadMessageNum()
                .setValue(privateUnread + groupUnread)
            if (privateUnread > 0) {
                binding.tabLayout.showMsg(TAB_PRIVATE, privateUnread)
            } else {
                binding.tabLayout.hideMsg(TAB_PRIVATE)
            }
        }
        group.setSessionsChangedListener { sessions ->
            groupUnread = sessions.filter {
                it.getType() == ChatConst.GROUP_CHANNEL && !it.isNoDisturb()
            }.sumOf {
                it.unreadNum()
            }
            LiveDataBus.of(BusEvent::class.java).unreadMessageNum()
                .setValue(privateUnread + groupUnread)
            if (groupUnread > 0) {
                binding.tabLayout.showMsg(TAB_GROUP, groupUnread)
            } else {
                binding.tabLayout.hideMsg(TAB_GROUP)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到会话页先查一次广场新帖，之后每 60s 轮询一次（仅在页面处于前台时）
        checkSquareUnread()
        squareUnreadJob?.cancel()
        squareUnreadJob = lifecycleScope.launch {
            while (isActive) {
                delay(SQUARE_CHECK_INTERVAL)
                if (!isResumed) break
                // 正在看广场时不必轮询：广场列表自身刷新会更新已读基准
                if (binding.vpMessage.currentItem != TAB_SQUARE) {
                    checkSquareUnread()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        squareUnreadJob?.cancel()
        squareUnreadJob = null
    }

    // ==================== 广场未读（新帖） ====================

    /**
     * 检查广场是否有新帖并更新「广场」tab 角标
     *
     * 广场服务端没有未读 / 通知接口（doc/广场2.md），因此按本地基准统计：
     * 拉最新流第 1 页，统计 id 大于「上次进入广场时看到的最新帖子 id」的帖子数。
     * 置顶帖 id 比新帖小，天然不会被算作未读。
     */
    private fun checkSquareUnread() {
        if (checkingSquareUnread || !isAdded || !SquareGate.canRead) return
        val ctx = requireContext().applicationContext
        checkingSquareUnread = true
        lifecycleScope.launch {
            val resp = SquareApi.feed(page = 1, pageSize = SQUARE_CHECK_PAGE_SIZE)
            checkingSquareUnread = false
            if (!resp.isSuccess || resp.data == null) return@launch
            val posts = resp.data?.items ?: emptyList()
            val newest = posts.maxOfOrNull { it.postId } ?: 0L
            // 首次安装 / 升级后没有基准：静默建立，不弹角标
            SquareUnread.ensureSeen(ctx, newest)
            if (view == null) return@launch
            if (binding.vpMessage.currentItem == TAB_SQUARE) {
                // 正在广场页：视为已读
                SquareUnread.markSeen(ctx, newest)
                showSquareUnread(0)
            } else {
                showSquareUnread(SquareUnread.countNew(ctx, posts))
            }
        }
    }

    /** 广场列表刷新完成回调：正在看广场时把最新帖子记为已读 */
    private fun onSquareFeedLoaded(newestId: Long) {
        if (!isAdded) return
        val ctx = context?.applicationContext ?: return
        SquareUnread.ensureSeen(ctx, newestId)
        if (binding.vpMessage.currentItem == TAB_SQUARE) {
            SquareUnread.markSeen(ctx, newestId)
            showSquareUnread(0)
        }
    }

    /** 更新「广场」tab 角标（0 = 隐藏） */
    private fun showSquareUnread(count: Int) {
        if (view == null) return
        if (count > 0) {
            binding.tabLayout.showMsg(TAB_SQUARE, count)
        } else {
            binding.tabLayout.hideMsg(TAB_SQUARE)
        }
    }

    override fun setEvent() {
        binding.ivScan.setOnClickListener(this)
        binding.ivSearch.setOnClickListener(this)
        binding.ivAdd.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.iv_scan -> {
                v.checkSingle(1000) { ARouter.getInstance().build(MainModule.QR_SCAN).navigation() }
            }

            R.id.iv_search -> {
                ARouter.getInstance().build(MainModule.SEARCH_LOCAL).navigation()
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