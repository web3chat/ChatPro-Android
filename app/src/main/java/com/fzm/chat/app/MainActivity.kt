package com.fzm.chat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.contains
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.arch.connection.logic.MessageDispatcher
import com.fzm.chat.app.databinding.ActivityMainBinding
import com.fzm.chat.biz.ai.AppContext
import com.fzm.chat.biz.ai.ConversationMessage
import com.fzm.chat.biz.ai.DeepSeekClient
import com.fzm.chat.biz.customerservice.AiCustomerService
import com.fzm.chat.app.databinding.LayoutDialogImportOldAccountBinding
import com.fzm.chat.biz.base.*
import com.fzm.chat.biz.bus.BusEvent
import com.fzm.chat.biz.bus.LiveDataBus
import com.fzm.chat.biz.bus.event.ChangeTabEvent
import com.fzm.chat.biz.bus.event.EndPointLoginEvent
import com.fzm.chat.biz.ui.DeepLinkHelper
import com.fzm.chat.biz.utils.NotificationHelper
import com.fzm.chat.biz.widget.pullheader.WechatPullContent
import com.fzm.chat.core.backup.LocalAccountManager
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.Contact
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.data.po.FriendUser
import com.fzm.chat.core.data.po.MessageContent
import com.fzm.chat.core.logic.MessageSender
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.UserInfo
import com.fzm.chat.router.app.AppModule
import com.fzm.chat.router.core.CoreModule
import com.fzm.chat.router.core.CoreService
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.main.MainService
import com.fzm.chat.router.route
import com.permissionx.guolindev.PermissionX
import com.fzm.chat.router.shop.ShopModule
import com.fzm.chat.router.shop.ShopService
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.fzm.widget.dialog.EasyDialog
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationMenuView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.zjy.architecture.base.instance
import com.zjy.architecture.data.Event
import com.zjy.architecture.data.EventObserver
import com.zjy.architecture.ext.*
import com.zjy.architecture.util.ActivityUtils
import com.zjy.architecture.util.KeyboardUtils
import com.zjy.architecture.util.other.BadgeUtil
import com.zjy.architecture.util.other.BarUtils
import dtalk.biz.Biz
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import walletapi.Walletapi

/**
 * @author zhengjy
 * @since 2020/12/07
 * Description:
 */
@Route(path = AppModule.MAIN, extras = AppConst.NEED_LOGIN)
class MainActivity : BizActivity() {

    /**
     * 需要导入的本地账户
     */
    @JvmField
    @Autowired
    var importAddress: String? = null

    /**
     * 首页所包含的一级Fragment
     */
    private val fragmentConfig = mutableListOf<Pair<String, Bundle?>>(
        MainModule.SESSION to null,
        MainModule.CONTACT to null,
        MainModule.FIND to null,
        WalletModule.WALLET to null,
        MainModule.ACCOUNT to null,
    )


    private val otherLoginObserver = Observer<EndPointLoginEvent?> {
        if (it != null) {
            delegate.performLogout()
            if (ActivityUtils.isBackground) {
                goLogin.value = Event(it)
            } else {
                ARouter.getInstance().build(MainModule.END_POINT_LOGIN)
                    .withString("deviceName", it.deviceName)
                    .withLong("datetime", it.datetime)
                    .navigation()
            }
        }
    }

    private val goLogin = MutableLiveData<Event<EndPointLoginEvent>>()

    private var bindDialog: EasyDialog? = null

    companion object {
        /** 存储权限预请求标记的 SharedPreferences */
        private const val PERMISSION_PREFS = "app_permission"
        /** 是否已请求过存储权限（只请求一次，避免每次回主界面弹框） */
        private const val KEY_STORAGE_REQUESTED = "storage_requested"
    }

    // 首次进入主界面是否已触发更新检查（冷启动/进程重建后才会检查，前后台切换不重复）
    private var updateCheckedOnLaunch = false
    /** 距上次收款地址同步尝试的时间戳（毫秒） */
    private var lastWalletSyncAttempt = 0L
    /** 最近一次收款地址同步是否成功（成功/失败使用不同的重试节流间隔） */
    private var walletSyncSucceed = false
    /** 收款地址同步成功后的检查节流间隔 */
    private val walletSyncRetryInterval = 5 * 60 * 1000L
    /** 收款地址同步失败后的快速重试节流间隔 */
    private val walletSyncFailRetryInterval = 30 * 1000L
    /** 是否已有一次待执行的失败重试，避免重复排队 */
    private var walletSyncRetryScheduled = false
    /** 网络连接管理：用于网络恢复时自动补传收款地址 */
    private val connectivityManager by lazy {
        getSystemService(ConnectivityManager::class.java)
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { retryWalletSync() }
        }
    }

    private val viewModel by viewModels<MainViewModel>()

    private val binding by init {
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementsUseOverlay = false
        ActivityMainBinding.inflate(layoutInflater)
    }
    private lateinit var unread: TextView
    private lateinit var msgItemView: BottomNavigationItemView

    private val delegate by inject<LoginDelegate>()
    private val dispatcher by inject<MessageDispatcher>()
    private val messageManager by inject<MessageSender>()
    private val mainService by route<MainService>(MainModule.SERVICE)
    private val walletService by route<WalletService>(WalletModule.SERVICE)
    private val coreService by route<CoreService>(CoreModule.SERVICE)
    private val shopService by route<ShopService>(ShopModule.SERVICE)
    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    // ==================== AI 问答相关 ====================
    /** AI问答面板根布局 */
    private var aiPanelRoot: View? = null
    /** AI问答面板内容区 */
    private var aiContentPanel: View? = null
    /** AI问答遮罩层 */
    private var aiMaskView: View? = null
    /** AI问答消息列表 */
    private var aiRvMessages: RecyclerView? = null
    /** AI问答输入框 */
    private var aiEtInput: EditText? = null
    /** AI问答发送按钮 */
    private var aiIvSend: ImageView? = null
    /** AI问答关闭按钮 */
    private var aiIvClose: ImageView? = null
    /** AI问答返回按钮（有聊天记录时显示） */
    private var aiIvBack: ImageView? = null
    /** 默认问题区域 */
    private var aiDefaultQuestions: LinearLayout? = null
    /** 默认问题1 */
    private var aiTvDefaultQ1: TextView? = null
    /** 默认问题2 */
    private var aiTvDefaultQ2: TextView? = null
    /** 刷新问题按钮 */
    private var aiTvRefresh: TextView? = null
    /** AI 客服悬浮按钮 */
    private var fabAiQa: View? = null
    /** AI 客服入口（AI 面板内，打开 BTY 智能客服 H5） */
    private var aiTvCsEntry: TextView? = null
    /** 键盘高度变化监听 */
    private var aiKeyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    /** 消息适配器 */
    private var aiMessageAdapter: AiMessageAdapter? = null
    /** 消息列表 */
    private val aiMessages = mutableListOf<AiChatMessage>()
    /** 是否正在等待AI回复 */
    private var aiIsWaitingReply = false
    /** 默认问题库（仅问题，答案由 DeepSeek 实时生成） */
    private val aiDefaultQuestionPool by lazy {
        listOf(
            getString(R.string.app_ai_question1),
            getString(R.string.app_ai_question2),
            getString(R.string.app_ai_question3),
            getString(R.string.app_ai_question4),
            getString(R.string.app_ai_question5),
            getString(R.string.app_ai_question6),
        )
    }
    /** 当前默认问题索引 */
    private var aiCurrentQuestionIndex = 0

    /**
     * AI聊天消息数据类
     */
    data class AiChatMessage(
        val content: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * AI消息RecyclerView适配器
     */
    private inner class AiMessageAdapter : RecyclerView.Adapter<AiMessageAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_chat_message, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val message = aiMessages[position]
            if (message.isUser) {
                holder.llUserMsg.visibility = View.VISIBLE
                holder.llAiMsg.visibility = View.GONE
                holder.tvUserMsg.text = message.content
            } else {
                holder.llUserMsg.visibility = View.GONE
                holder.llAiMsg.visibility = View.VISIBLE
                holder.tvAiMsg.text = message.content
            }
        }

        override fun getItemCount(): Int = aiMessages.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val llUserMsg: LinearLayout = itemView.findViewById(R.id.ll_user_msg)
            val tvUserMsg: TextView = itemView.findViewById(R.id.tv_user_msg)
            val llAiMsg: LinearLayout = itemView.findViewById(R.id.ll_ai_msg)
            val tvAiMsg: TextView = itemView.findViewById(R.id.tv_ai_msg)
        }
    }

    override val root: View
        get() = binding.root

    override fun setSystemBar() {
        notchSupport(window)
        BarUtils.setStatusBarColor(this, ContextCompat.getColor(this, android.R.color.transparent), 0)
        BarUtils.setStatusBarLightMode(this, true)
        window?.navigationBarColor = ContextCompat.getColor(this, R.color.biz_color_primary)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // 更新检查已统一到 onResume 的首次标志位逻辑（冷启动与进程重建均会触发）
    }

    override fun initView() {
        // 去掉底部导航选中放大效果：固定 labeled 模式并关闭所有 item 的 shifting（图标/文字选中不上移不放大）
        //binding.bottomNav.labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
        (binding.bottomNav.getChildAt(0) as? BottomNavigationMenuView)?.let { menuView ->
            for (i in 0 until menuView.childCount) {
                (menuView.getChildAt(i) as? BottomNavigationItemView)?.setShifting(false)
            }
        }
        ARouter.getInstance().inject(this)
        binding.vpMain.adapter = object :
            FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            override fun getItem(position: Int): Fragment {
                val fragment by route<Fragment>(fragmentConfig[position]) {
                    EmptyTabFragment.create()
                }
                return fragment!!
            }

            override fun getCount(): Int {
                return fragmentConfig.size
            }

            override fun getItemId(position: Int): Long {
                return fragmentConfig[position].first[position].toLong()
            }
        }
        binding.vpMain.offscreenPageLimit = 5
        binding.vpMain.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {}

            override fun onPageSelected(position: Int) {

            }

            override fun onPageScrollStateChanged(state: Int) {}
        })

        /*     delegate.current.observe(this, object : Observer<UserInfo> {
                 override fun onChanged(info: UserInfo) {
                     if (info.isLogin()) {
                         if (info.phone.isNullOrEmpty() && info.email.isNullOrEmpty()) {
                             if (viewModel.showBind.value == true) {
                                 bindDialog = EasyDialog.Builder()
                                     .setHeaderTitle(getString(R.string.biz_tips))
                                     .setBottomLeftText(getString(R.string.app_skip_bind_phone))
                                     .setBottomRightText(getString(R.string.app_skip_bind_email))
                                     .setContent(getString(R.string.app_skip_bind_tips))
                                     .setBottomLeftClickListener { dialog ->
                                         dialog.dismiss()
                                         ARouter.getInstance().build(MainModule.SECURITY_SET)
                                             .withInt("bindType", ChatConst.PHONE)
                                             .navigation()
                                     }
                                     .setBottomRightClickListener { dialog ->
                                         dialog.dismiss()
                                         ARouter.getInstance().build(MainModule.SECURITY_SET)
                                             .withInt("bindType", ChatConst.EMAIL)
                                             .navigation()
                                     }
                                     .create(instance)
                                 bindDialog?.show()
                                 viewModel.showBind.value = false
                             }
                         } else {
                             if (bindDialog?.isShowing == true) {
                                 bindDialog?.dismiss()
                             }
                             delegate.current.removeObserver(this)
                         }
                     } else {
                         if (bindDialog?.isShowing == true) {
                             bindDialog?.dismiss()
                         }
                         delegate.current.removeObserver(this)
                     }
                 }
             })*/
        DeepLinkHelper.dispatchDeepLink(intent)
        // 处理 H5 唤醒场景：App 被杀死后登录完成，消费待定的聊天参数
        // 注意：LoginInterceptor 有 bug，会在登录检查前先 onContinue，导致 MainActivity 提前创建，
        // 所以必须加 isLogin() 判断，避免未登录时提前消费参数
        if (delegate.isLogin()) {
            consumePendingChatParams()
        }
        // 首次启动主页面，请求更新用户信息
        delegate.updateInfo()
        // 初始化AI问答面板
        initAiPanel()
        enableAiFabDrag()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        importAddress = intent?.getStringExtra("importAddress")
        DeepLinkHelper.dispatchDeepLink(intent)
        // 从登录页返回时，检查并消费待定的H5聊天参数
        if (delegate.isLogin()) {
            consumePendingChatParams()
        }
        importLocalAccount()
    }

    /**
     * 消费 DeepLinkHelper 中待定的 H5 聊天参数，跳转到 ChatActivity
     */
    private fun consumePendingChatParams() {
        DeepLinkHelper.consumePendingChatParams()?.let { (address, channelType) ->
            ARouter.getInstance().build(MainModule.CHAT)
                .withString("address", address)
                .withInt("channelType", channelType)
                .navigation()
        }
    }

    override fun initData() {
        LiveDataBus.of(BusEvent::class.java).unreadMessageNum().observe(this) {
            BadgeUtil.setBadgeCount(this, it)
            showMsgBadge(it)
        }
        LiveDataBus.of(BusEvent::class.java).changeTab().observe(this) {
            binding.vpMain.currentItem = it.tab
        }
        LiveDataBus.of(BusEvent::class.java).sessionPullState().observe(this) {
            when (it) {
                WechatPullContent.CLOSED -> {

                }
                WechatPullContent.OPENING -> {
                    binding.bottomNav.clearAnimation()
                    binding.bottomNav.gone()
                    binding.bottomNav.startAnimation(hideAnimation)
                }
                WechatPullContent.OPENED -> {

                }
                WechatPullContent.CLOSING -> {
                    binding.bottomNav.clearAnimation()
                    binding.bottomNav.visible()
                    binding.bottomNav.startAnimation(showAnimation)

                }
            }
        }
        goLogin.observe(this, EventObserver {
            ARouter.getInstance().build(MainModule.END_POINT_LOGIN)
                .withString("deviceName", it.deviceName)
                .withLong("datetime", it.datetime)
                .navigation()
        })
        LiveDataBus.of(BusEvent::class.java).endPointLogin().observeForever(otherLoginObserver)
        MessageSubscription.setOnShowNotification {
            NotificationHelper.showNotification(it)
        }
        viewModel.shopModule.observe(this) {
            if (it) {
                addShopTab()
            }
        }
        if (FunctionModules.enableShop) {
            viewModel.shopModule.value = true
        }
        if (FunctionModules.enableWallet) {
            viewModel.walletModule.value = true
        }
        ActivityUtils.addOnAppStateChangedListener(object : ActivityUtils.OnAppStateChangedListener {
            override fun onLeaveApp() {
            }

            override fun onResumeApp() {
                goLogin.value?.getContentIfNotHandled()?.also {
                    ARouter.getInstance().build(MainModule.END_POINT_LOGIN)
                        .withString("deviceName", it.deviceName)
                        .withLong("datetime", it.datetime)
                        .navigation()
                }
            }
        })
    }

    private fun addShopTab() {
        binding.bottomNav.menu.add(0, R.id.tab_shop, 3, R.string.app_tab_shop)
            .setIcon(R.drawable.app_tab_shop_selector)
    }

    private val hideAnimation = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
        Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 1f
    ).apply {
        interpolator = DecelerateInterpolator()
        duration = 250
        fillAfter = true
    }

    private val showAnimation = TranslateAnimation(
        Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
        Animation.RELATIVE_TO_SELF, 1f, Animation.RELATIVE_TO_SELF, 0f
    ).apply {
        interpolator = DecelerateInterpolator()
        duration = 250
        fillAfter = true
    }

    private fun showMsgBadge(number: Int?) {
        if (number == null) return
        if (!this::msgItemView.isInitialized) {
            val menuView = binding.bottomNav.getChildAt(0) as BottomNavigationMenuView
            msgItemView = menuView.findViewById(R.id.tab_session)
        }
        if (!this::unread.isInitialized) {
            unread = layoutInflater.inflate(R.layout.layout_unread_dot, msgItemView, false) as TextView
        }
        if (!msgItemView.contains(unread)) {
            msgItemView.addView(unread)
            unread.layoutParams = (unread.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = (msgItemView.measuredWidth / 2) - 20.dp
                topMargin = 5.dp
            }
        }
        when {
            number > 99 -> {
                if (BuildConfig.DEBUG) {
                    unread.text = "$number"
                } else {
                    unread.text = "99+"
                }
                unread.visible()
            }
            number > 0 -> {
                unread.text = "$number"
                unread.visible()
            }
            else -> unread.gone()
        }
    }

    override fun setEvent() {
        binding.bottomNav.run {
            // 去除icon着色
            itemIconTintList = null
            setOnNavigationItemSelectedListener {
                if (binding.bottomNav.isGone) return@setOnNavigationItemSelectedListener true
                when (it.itemId) {
                    R.id.tab_session -> binding.vpMain.setCurrentItem(0, false)
                    R.id.tab_contract -> binding.vpMain.setCurrentItem(1, false)
                    R.id.tab_shop -> {
                        shopService?.openShopHomePage(instance)
                        return@setOnNavigationItemSelectedListener false
                    }
                    R.id.tab_find -> {
                        binding.vpMain.setCurrentItem(2, false)
                    }
                    R.id.tab_wallet -> {
                        binding.vpMain.setCurrentItem(3, false)
                    }
                    R.id.tab_account -> {
                        binding.vpMain.setCurrentItem(4, false)
                    }
                }
                return@setOnNavigationItemSelectedListener true
            }
        }
        binding.vpMain.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {

            }

            override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {

            }

            override fun onPageSelected(position: Int) {
                if (viewModel.shopModule.value == true) {
                    // 如果启用了商城模块，因为商城模块的特殊性（不创建单独的Fragment），导致position发生偏移
                    if (position > 1) {
                        binding.bottomNav.selectedItemId = binding.bottomNav.menu[position + 1].itemId
                    } else {
                        binding.bottomNav.selectedItemId = binding.bottomNav.menu[position].itemId
                    }
                } else {
                    binding.bottomNav.selectedItemId = binding.bottomNav.menu[position].itemId
                }
            }
        })
    }

    /**
     * 导入本地其他账户
     */
    private fun importLocalAccount() {
        importAddress?.also { account ->
            lifecycleScope.launch {
                val manager by inject<LocalAccountManager>()
                val layout = LayoutDialogImportOldAccountBinding.inflate(layoutInflater).apply {
                    cbSelect.setOnCheckedChangeListener { _, isChecked ->
                        etMsg.isEnabled = isChecked
                        if (isChecked) {
                            KeyboardUtils.showKeyboard(etMsg)
                        } else {
                            KeyboardUtils.hideKeyboard(etMsg)
                        }
                    }
                }
                EasyDialog.Builder()
                    .setHeaderTitle(getString(R.string.app_import_friends_title))
                    .setContent(getString(R.string.app_import_friends_content))
                    .setView(layout.root)
                    .setBottomLeftText(getString(R.string.biz_cancel))
                    .setBottomRightText(getString(R.string.app_import_friends_title))
                    .setBottomRightClickListener {
                        lifecycleScope.launch {
                            val text by lazy(LazyThreadSafetyMode.NONE) { layout.etMsg.text.trim().toString() }
                            if (layout.etMsg.isEnabled && text.isEmpty()) {
                                toast(getString(R.string.app_message_empty))
                            } else {
                                loading(false)
                                val result = manager.importByOldAddress(account)
                                dismiss()
                                if (result.isSucceed()) {
                                    it.dismiss()
                                    toast(getString(R.string.app_import_success))
                                    sendNotifyToFriends(text)
                                    LiveDataBus.of(BusEvent::class.java).changeTab().postValue(ChangeTabEvent(1, 0))

                                } else {
                                    toast(result.error().message ?: "")
                                }
                            }
                        }
                    }
                    .create(this@MainActivity)
                    .show()
                // 执行过一次导入后重置参数
                this@MainActivity.importAddress = null
            }
        }
    }

    /**
     * 给所有好友发送一条消息
     */
    private suspend fun sendNotifyToFriends(text: String?) {
        if (text.isNullOrEmpty()) return
        val liveData = database.friendUserDao().getFriendUserList(Contact.RELATION)
        liveData.observe(this, object : Observer<List<FriendUser>> {
            override fun onChanged(t: List<FriendUser>?) {
                if (t.isNullOrEmpty()) return
                liveData.removeObserver(this)
                t.forEach {
                    val message = ChatMessage.create(
                        delegate.getAddress(),
                        it.address,
                        ChatConst.PRIVATE_CHANNEL,
                        Biz.MsgType.Text,
                        MessageContent.text(text)
                    )
                    val servers = it.getServerList()
                    if (servers.isNotEmpty()) {
                        messageManager.send(servers[0].address, message)
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        coreService?.checkService()
        setCustomDensity(this)
        importLocalAccount()
        // 首次进入主界面预请求存储（相册）权限，避免进聊天页点「图片/视频」时才首次申请
        requestStoragePermissionOnce()
        // 首次启动（含进程重建）进入主界面后延迟检查更新。
        // 原逻辑在 SplashActivity 同步等待 checkUpdate，弱网下会导致启动页长时间卡顿，已改为此处异步执行。
        if (!updateCheckedOnLaunch) {
            updateCheckedOnLaunch = true
            binding.root.postDelayed({ mainService?.checkUpdate(this) }, 1500)
        }
        // 兜底同步收款地址：首次必试；失败后 30 秒自动重试，回主界面/网络恢复时也会再次补传（幂等）
        retryWalletSync()
        // 监听网络连接：断网导致的同步失败在网络恢复后自动补传
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    /**
     * 首次进入主界面时申请一次存储（相册）权限
     *
     * 目的：避免用户进入聊天页点「图片/视频」时才首次申请权限——个别华为机型
     * 在此时刚弹完权限框就拉起相册容易出现异常/闪退。
     *
     * 只申请一次（本地标记），用户拒绝也不影响使用；聊天页入口仍保留自己的申请逻辑。
     */
    private fun requestStoragePermissionOnce() {
        if (!ChatConfig.AUTO_REQUEST_STORAGE) return
        try {
            val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_STORAGE_REQUESTED, false)) return
            val permissions = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val granted = permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            // 无论是否已授权，都标记为已处理，避免每次回主界面重复弹框
            prefs.edit().putBoolean(KEY_STORAGE_REQUESTED, true).apply()
            if (granted) return
            PermissionX.init(this)
                .permissions(permissions)
                .request { _, _, _ -> }
        } catch (e: Throwable) {
            Log.e("Permission", "预请求存储权限失败：${e.message}")
        }
    }

    /**
     * 按节流间隔补传收款地址；成功后 5 分钟内不再尝试，失败则 30 秒后自动重试（成功后停止）。
     * 失败的尝试不占用成功节流额度，避免弱网/冷启动时白白错过后续重试时机。
     */
    private fun retryWalletSync() {
        val now = System.currentTimeMillis()
        val interval = if (walletSyncSucceed) walletSyncRetryInterval else walletSyncFailRetryInterval
        if (now - lastWalletSyncAttempt < interval) return
        lastWalletSyncAttempt = now
        lifecycleScope.launch {
            val ret = runCatching { walletService?.updateChainAddress(null) }.getOrNull()
            walletSyncSucceed = ret == "ok"
            if (!walletSyncSucceed) {
                Log.e("ChainSync", "收款地址同步失败：$ret 将在下次回到主界面、网络恢复或 30 秒后自动重试")
                // 失败后安排一次自动重试（只排队一次），成功即停
                if (!walletSyncRetryScheduled) {
                    walletSyncRetryScheduled = true
                    lifecycleScope.launch {
                        delay(walletSyncFailRetryInterval + 5_000L)
                        walletSyncRetryScheduled = false
                        retryWalletSync()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        aiKeyboardListener?.let { aiPanelRoot?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        LiveDataBus.of(BusEvent::class.java).endPointLogin().setValue(null)
        LiveDataBus.of(BusEvent::class.java).endPointLogin().removeObserver(otherLoginObserver)
        // dispatcher / messageManager 是全局单例，生命周期跟随登录状态，
        // 由 LoginDelegate.logoutSuspend() 统一管理，不在 Activity 销毁时 dispose
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 如果AI问答面板打开，先关闭面板
        if (aiPanelRoot?.isVisible == true) {
            hideAiPanel()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val intent = Intent()
            intent.action = Intent.ACTION_MAIN
            intent.addCategory(Intent.CATEGORY_HOME)
            startActivity(intent)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ==================== AI 问答面板逻辑 ====================

    /**
     * AI 悬浮按钮：支持在屏幕内自由拖动（拖动后停在新位置，轻点仍展开 AI 面板）
     */
    private fun enableAiFabDrag() {
        val fab = fabAiQa ?: return
        val container = fab.parent as? ViewGroup ?: return
        fab.post {
            val fabW = fab.width
            val fabH = fab.height
            val maxX = (container.width - fabW).coerceAtLeast(0)
            val maxY = (container.height - fabH).coerceAtLeast(0)
            val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
            var downRawX = 0f
            var downRawY = 0f
            var baseLeft = 0
            var baseTop = 0
            var dragging = false
            fab.setOnTouchListener { v, event ->
                val lp = v.layoutParams as FrameLayout.LayoutParams
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        baseLeft = v.left
                        baseTop = v.top
                        dragging = false
                        // 切换到左/上绝对定位，便于自由拖动
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = baseLeft
                        lp.topMargin = baseTop
                        v.layoutParams = lp
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) {
                            val dist = Math.abs(event.rawX - downRawX) + Math.abs(event.rawY - downRawY)
                            if (dist > slop) dragging = true
                        }
                        if (dragging) {
                            val newLeft = (baseLeft + (event.rawX - downRawX)).toInt().coerceIn(0, maxX)
                            val newTop = (baseTop + (event.rawY - downRawY)).toInt().coerceIn(0, maxY)
                            if (lp.leftMargin != newLeft || lp.topMargin != newTop) {
                                lp.leftMargin = newLeft
                                lp.topMargin = newTop
                                v.layoutParams = lp
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 未发生拖动视为点击
                        if (!dragging) v.performClick()
                        true
                    }

                    else -> true
                }
            }
        }
    }

    /**
     * 初始化AI问答面板
     */
    private fun initAiPanel() {
        // 获取面板视图
        aiPanelRoot = findViewById(R.id.ai_qa_panel)
        aiMaskView = aiPanelRoot?.findViewById(R.id.v_mask)
        aiContentPanel = aiPanelRoot?.findViewById(R.id.rl_ai_content)
        aiRvMessages = aiPanelRoot?.findViewById(R.id.rv_ai_messages)
        aiEtInput = aiPanelRoot?.findViewById(R.id.et_ai_input)
        aiIvSend = aiPanelRoot?.findViewById(R.id.iv_ai_send)
        aiIvClose = aiPanelRoot?.findViewById(R.id.iv_ai_close)
        aiIvBack = aiPanelRoot?.findViewById(R.id.iv_ai_back)
        aiDefaultQuestions = aiPanelRoot?.findViewById(R.id.ll_default_questions)
        aiTvDefaultQ1 = aiPanelRoot?.findViewById(R.id.tv_default_q1)
        aiTvDefaultQ2 = aiPanelRoot?.findViewById(R.id.tv_default_q2)
        aiTvRefresh = aiPanelRoot?.findViewById(R.id.tv_refresh)
        fabAiQa = findViewById(R.id.fab_ai_qa)
        aiTvCsEntry = aiPanelRoot?.findViewById(R.id.tv_ai_cs_entry)

        // 初始化RecyclerView
        aiMessageAdapter = AiMessageAdapter()
        aiRvMessages?.layoutManager = LinearLayoutManager(this)
        aiRvMessages?.adapter = aiMessageAdapter

        // 设置默认问题
        updateDefaultQuestions()

        // 悬浮按钮点击 - 直接进入 AI 客服（与「发现」页 AI 客服入口完全一致）
        fabAiQa?.setOnClickListener {
            openAiCustomerService()
        }

        // AI 客服入口：直接用 DApp 浏览器打开 BTY 智能客服（DappActivity 会注入 EIP-1193 钱包，
        // 页面「连接钱包」可用）；不再走自建客服页（CustomerServiceActivity 保留但不再引用）
        aiTvCsEntry?.setOnClickListener {
            hideAiPanel()
            openAiCustomerService()
        }

        // 遮罩层点击 - 关闭AI面板
        aiMaskView?.setOnClickListener {
            hideAiPanel()
        }

        // 关闭按钮
        aiIvClose?.setOnClickListener {
            hideAiPanel()
        }

        // 返回按钮 - 回到默认问题选择
        aiIvBack?.setOnClickListener {
            backToDefaultQuestions()
        }

        // 发送按钮点击
        aiIvSend?.setOnClickListener {
            sendAiMessage()
        }

        // 默认问题1点击
        aiTvDefaultQ1?.setOnClickListener {
            val question = aiTvDefaultQ1?.text?.toString() ?: return@setOnClickListener
            sendAiMessage(question)
        }

        // 默认问题2点击
        aiTvDefaultQ2?.setOnClickListener {
            val question = aiTvDefaultQ2?.text?.toString() ?: return@setOnClickListener
            sendAiMessage(question)
        }

        // 刷新问题按钮
        aiTvRefresh?.setOnClickListener {
            aiCurrentQuestionIndex = (aiCurrentQuestionIndex + 2) % aiDefaultQuestionPool.size
            updateDefaultQuestions()
        }

        // 输入框文字变化监听，控制发送按钮状态
        aiEtInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = !s.isNullOrBlank()
                aiIvSend?.alpha = if (hasText) 1.0f else 0.4f
                aiIvSend?.isEnabled = hasText
            }
        })
        // 初始状态发送按钮置灰
        aiIvSend?.alpha = 0.4f
        aiIvSend?.isEnabled = false

        // 键盘高度监听 — 自动抬高输入区域
        setupKeyboardListener()
    }

    /**
     * 更新默认问题显示
     */
    private fun updateDefaultQuestions() {
        val idx = aiCurrentQuestionIndex % aiDefaultQuestionPool.size
        val nextIdx = (aiCurrentQuestionIndex + 1) % aiDefaultQuestionPool.size
        aiTvDefaultQ1?.text = aiDefaultQuestionPool[idx]
        aiTvDefaultQ2?.text = aiDefaultQuestionPool[nextIdx]
    }

    /**
     * 打开 AI 客服（与「发现」页入口完全一致）：走 DApp 浏览器打开 BTY 智能客服 H5，
     * DappActivity 会注入 window.ethereum，页面里的「连接钱包」可用；不显示标题栏。
     */
    private fun openAiCustomerService() {
        hideAiPanel()
        AiCustomerService.open(this)
    }

    /**
     * 显示AI问答面板
     */
    private fun showAiPanel() {
        aiPanelRoot?.visibility = View.VISIBLE
        aiContentPanel?.translationY = 0f
        aiEtInput?.requestFocus()
        aiEtInput?.let { KeyboardUtils.showKeyboard(it) }
    }

    /**
     * 隐藏AI问答面板
     */
    private fun hideAiPanel() {
        aiEtInput?.let { KeyboardUtils.hideKeyboard(it) }
        aiContentPanel?.translationY = 0f
        aiPanelRoot?.visibility = View.GONE
    }

    /**
     * 监听键盘弹出/收起，自动调整 AI 面板高度防止输入框被遮挡
     */
    private fun setupKeyboardListener() {
        aiKeyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rootView = aiPanelRoot ?: return@OnGlobalLayoutListener
            if (rootView.visibility != View.VISIBLE) return@OnGlobalLayoutListener

            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keyboardHeight = (screenHeight - rect.bottom).coerceAtLeast(0)

            // 键盘高度超过 100px 才认为是弹出了键盘（排除导航栏干扰）
            if (keyboardHeight > 100) {
                aiContentPanel?.translationY = -keyboardHeight.toFloat()
            } else {
                aiContentPanel?.translationY = 0f
            }
        }
        aiPanelRoot?.viewTreeObserver?.addOnGlobalLayoutListener(aiKeyboardListener)
    }

    /**
     * 返回到默认问题选择界面
     */
    private fun backToDefaultQuestions() {
        // 清空聊天记录
        aiMessages.clear()
        aiMessageAdapter?.notifyDataSetChanged()
        // 显示默认问题区域
        aiDefaultQuestions?.visibility = View.VISIBLE
        // 隐藏返回按钮
        aiIvBack?.visibility = View.GONE
        // 刷新默认问题
        aiCurrentQuestionIndex = (aiCurrentQuestionIndex + 2) % aiDefaultQuestionPool.size
        updateDefaultQuestions()
    }

    /**
     * 发送AI消息（对接 DeepSeek API — 流式输出增强版）
     */
    private fun sendAiMessage(question: String? = null) {
        val text = question ?: aiEtInput?.text?.toString()?.trim() ?: return
        if (text.isEmpty() || aiIsWaitingReply) return

        // 添加用户消息到界面
        aiMessages.add(AiChatMessage(content = text, isUser = true))
        aiMessageAdapter?.notifyItemInserted(aiMessages.size - 1)
        aiRvMessages?.scrollToPosition(aiMessages.size - 1)

        // 隐藏默认问题区域
        aiDefaultQuestions?.visibility = View.GONE
        // 显示返回按钮
        aiIvBack?.visibility = View.VISIBLE

        // 清空输入框
        if (question == null) {
            aiEtInput?.text?.clear()
        }

        // 显示等待状态
        aiIsWaitingReply = true
        aiIvSend?.alpha = 0.4f
        aiIvSend?.isEnabled = false

        // 先插入一个空的 AI 占位消息，后续流式更新
        aiMessages.add(AiChatMessage(content = "", isUser = false))
        val aiReplyIndex = aiMessages.size - 1
        aiMessageAdapter?.notifyItemInserted(aiReplyIndex)
        aiRvMessages?.scrollToPosition(aiReplyIndex)

        // 构建用户上下文
        val userInfo = delegate.current.value
        val appContext = AppContext(
            nickname = userInfo?.nickname ?: "",
            currentPage = getCurrentPageName(),
            extra = "App 版本：${BuildConfig.VERSION_NAME}"
        )

        // 调用 DeepSeek 流式 API
        lifecycleScope.launch {
            // 构建对话历史（排除刚添加的空占位消息）
            val conversationHistory = aiMessages
                .filterIndexed { index, _ -> index != aiReplyIndex }
                .map { msg -> ConversationMessage(content = msg.content, isUser = msg.isUser) }

            val replyBuffer = StringBuilder()
            DeepSeekClient.chatStream(
                messages = conversationHistory,
                onChunk = { chunk ->
                    replyBuffer.append(chunk)
                    // 实时更新界面中的 AI 消息（切回主线程）
                    aiRvMessages?.post {
                        aiMessages[aiReplyIndex] = AiChatMessage(
                            content = replyBuffer.toString(),
                            isUser = false
                        )
                        aiMessageAdapter?.notifyItemChanged(aiReplyIndex)
                        aiRvMessages?.scrollToPosition(aiReplyIndex)
                    }
                },
                context = this@MainActivity,
                appCtx = appContext
            )

            aiIsWaitingReply = false
            val inputText = aiEtInput?.text?.toString() ?: ""
            val hasInput = inputText.isNotBlank()
            aiIvSend?.alpha = if (hasInput) 1.0f else 0.4f
            aiIvSend?.isEnabled = hasInput
        }
    }

    /**
     * 获取当前页面名称（用于 AI 上下文）
     */
    private fun getCurrentPageName(): String {
        val position = binding.vpMain.currentItem
        // fragmentConfig 定义：消息、通讯录、发现、钱包、我的
        return when {
            viewModel.shopModule.value == true -> when (position) {
                0 -> getString(R.string.app_tab_session)
                1 -> getString(R.string.app_tab_contact)
                2 -> "商城"
                3 -> getString(R.string.app_tab_find)
                4 -> getString(R.string.app_tab_wallet)
                5 -> getString(R.string.app_tab_account)
                else -> "首页"
            }
            else -> when (position) {
                0 -> getString(R.string.app_tab_session)
                1 -> getString(R.string.app_tab_contact)
                2 -> getString(R.string.app_tab_find)
                3 -> getString(R.string.app_tab_wallet)
                4 -> getString(R.string.app_tab_account)
                else -> "首页"
            }
        }
    }
}