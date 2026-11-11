package com.fzm.chat.core.di

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.map
import androidx.lifecycle.MediatorLiveData
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.fzm.arch.connection.OnMessageConfirmCallback
import com.fzm.arch.connection.logic.MessageDispatcher
import com.fzm.arch.connection.processor.Processor
import com.fzm.arch.connection.socket.ChatSocket
import com.fzm.arch.connection.socket.ConnectionManager
import com.fzm.arch.connection.socket.tcp.TcpSocket
import com.fzm.arch.connection.socket.ws.OKWebSocket
import com.fzm.arch.connection.utils.urlKey
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.core.R
import com.fzm.chat.core.backup.LocalAccountManager
import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.ChatConfig
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.CoreDatabase
import com.fzm.chat.core.data.bean.Server
import com.fzm.chat.core.data.local.ContactManager
import com.fzm.chat.core.data.local.MemoryContactSource
import com.fzm.chat.core.data.model.ChatMessage
import com.fzm.chat.core.logic.HttpSender
import com.fzm.chat.core.logic.MessageSender
import com.fzm.chat.core.logic.MessageSubscription
import com.fzm.chat.core.logic.ServerManager
import com.fzm.chat.core.logic.SocketSender
import com.fzm.chat.core.logic.BurnConfirmScheduler
import com.fzm.chat.core.net.ChatInterceptor
import com.fzm.chat.core.net.DecryptInterceptor
import com.fzm.chat.core.net.TrustAllCertificate
import com.fzm.chat.core.net.TrustAllHostnameVerifier
import com.fzm.chat.core.net.api.*
import com.fzm.chat.core.net.source.*
import com.fzm.chat.core.net.source.impl.LocalSearchDataSource
import com.fzm.chat.core.net.source.impl.NetBackupDataSource
import com.fzm.chat.core.net.source.impl.NetContractDataSource
import com.fzm.chat.core.net.source.impl.NetGroupDataSource
import com.fzm.chat.core.processor.*
import com.fzm.chat.core.repo.*
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.LoginDelegateImpl
import com.fzm.chat.core.session.UserDataSource
import com.fzm.chat.core.session.api.UserService
import com.fzm.chat.core.session.data.NetUserDataSource
import com.fzm.chat.core.session.data.UserRepository
import com.fzm.chat.router.core.CoreModule
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.Expose
import com.zjy.architecture.Arch
import com.zjy.architecture.di.Injector
import com.zjy.architecture.ext.addChangeUrlInterceptor
import com.zjy.architecture.ext.addInterceptors
import com.zjy.architecture.ext.addSSLSocketFactory
import com.zjy.architecture.ext.createOkHttpClient
import com.zjy.architecture.ext.createRetrofit
import com.zjy.architecture.ext.notificationManager
import dtalk.biz.Biz
import me.jessyan.retrofiturlmanager.RetrofitUrlManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import walletapi.Config
import walletapi.Walletapi
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * @author zhengjy
 * @since 2020/12/07
 * Description:
 */
@Route(path = CoreModule.INJECTOR)
class CoreInjector : Injector, IProvider {

    companion object {
        val contract = named("Contract")
        val socket = named("Socket")
        val crypto = named("Crypto")
        val decryptInterceptor = named("decryptInterceptor")
    }

    override fun inject() = module {
        netModule()
        messageProcessors()
        messageConfirmCallback()
        factory<ChatSocket>(named("TcpSocket")) { TcpSocket(it.component1()) }
        factory<ChatSocket>(named("WebSocket")) { OKWebSocket(it.component1(), get(socket)) }
        single {
            val delegate: LoginDelegate = get()
            // 只连接「当前选中的聊天服务器」：
            // 用户信息里会下发多个聊天服务器，全量建连会让每个服务器各自无限制重连（耗电、刷日志、
            // 且不可达的服务器会一直刷“第 N 次重连”）。这里只保留选中的那一个。
            // 选中地址来自 AppPreference.CHAT_URL（登录/切换服务器时写入），
            // 未选择或不在下发列表里时回退到列表第一个。
            // 另外监听 ServerManager.chatServerChanged：用户在登录/服务器页切换后立即重建连接。
            val serverList = MediatorLiveData<List<String>>().apply {
                fun applySelected(servers: List<Server>?) {
                    value = pickSelectedServer(servers.orEmpty())
                }
                addSource(delegate.servers) { applySelected(it) }
                addSource(ServerManager.chatServerChanged) {
                    applySelected(delegate.servers.value)
                }
            }
            ConnectionManager(serverList, get(named("ConfirmCallback")))
        }
        single { SocketSender(get(), get(), get()) }
        single { HttpSender(get(), get(), get(), get()) }
        single { MessageSender(get(), get(), get()) }
        single {
            val delegate: LoginDelegate = get()
            // 用 delegate.isLogin()（内部对冷启动未就绪做了本地地址兜底），
            // 否则连接刚建立时 current 首值未到，认证帧/消息会被当未登录丢掉
            val isLogin = { delegate.isLogin() }
            MessageDispatcher(isLogin, get(), get(named("ApiProto")))
        }
        single {
            Walletapi.newChainClient(Config())
        }

        single { MemoryContactSource(get()) }
        single { ContactManager(get(), get(), get(), get()) }

        single<UserDataSource> {
            NetUserDataSource(get<Retrofit>(contract).create(UserService::class.java), get())
        }
        single { UserRepository(get(), get()) }
        single<LoginDelegate>(createdAtStart = true) { LoginDelegateImpl(get(), get()) }

        single<ContractDataSource> {
            NetContractDataSource(get<Retrofit>(contract).create(ContractService::class.java))
        }
        single { ContractRepository(get(), get(), get()) }
        single {
            val service =
                get<Retrofit>(contract).create(TransactionSource.ChatTransactionService::class.java)
            TransactionSource(TransactionSource.ChatTransactionDelegate(service), get())
        }
        single<GroupDataSource> {
            NetGroupDataSource(get<Retrofit>().create(GroupService::class.java))
        }
        single { GroupRepository(get()) }

        factory<BackupDataSource> { NetBackupDataSource(get<Retrofit>().create(BackupService::class.java)) }
        factory { BackupRepository(get()) }
        // 本地搜索
        factory { SearchRepository(get(), get()) }
        single<SearchDataSource> { LocalSearchDataSource() }
        // RTC相关通信接口
        single { RTCRepository(get<Retrofit>().create(RTCService::class.java)) }
        // 聊天服务器相关接口
        single { ChatRepository(get<Retrofit>().create(ChatService::class.java)) }

        // Core数据库暂时用的比较少，暂且用factory
        factory { CoreDatabase.build(get()) }
        factory { LocalAccountManager(get(), get()) }
    }

    private fun Module.netModule() {
        single {
            GsonBuilder()
                .addSerializationExclusionStrategy(serializeExclusionStrategy)
                .addDeserializationExclusionStrategy(deserializeExclusionStrategy)
                .create()
        }
        single<Interceptor> { ChatInterceptor(get()) }
        single<Interceptor>(decryptInterceptor) { DecryptInterceptor(get()) }
        single {
            RetrofitUrlManager.getInstance().apply {
                putDomain(ChatConst.CENTRALIZED_DOMAIN, ChatConfig.CENTRALIZED_SERVER)
                putDomain(ChatConst.GO_NEW_DOMAIN, ChatConfig.GO_NEW_SERVER)
                putDomain(ChatConst.WALLET_DOMAIN, ChatConfig.GO_URL)
                putDomain(ChatConst.PHP_DOMAIN, ChatConfig.BASE_URL)
            }
        }
        single {
            createOkHttpClient(get(), null, null, get(), get(decryptInterceptor))
        }
        single {
            createRetrofit(get(), get(), "https://0.0.0.0")
        }
        single(contract) {
            if (Arch.debug) {
                createOkHttpClient(
                    get(),
                    TrustAllCertificate.createSSLSocketFactory(),
                    TrustAllCertificate()
                )
                    .newBuilder()
                    .callTimeout(10, TimeUnit.SECONDS)
                    .hostnameVerifier(TrustAllHostnameVerifier())
                    .build()
            } else {
                createOkHttpClient(get(), null, null)
                    .newBuilder().callTimeout(10, TimeUnit.SECONDS)
                    .build()
            }
        }
        single(contract) {
            createRetrofit(get(contract), get(), "https://0.0.0.0")
        }
        single(socket) {
            OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        }
        single(crypto) {
            OkHttpClient.Builder()
                .connectTimeout(20L, TimeUnit.SECONDS)
                .readTimeout(20L, TimeUnit.SECONDS)
                .writeTimeout(20L, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun Module.messageProcessors() {
        single(named("BizMessage")) {
            mutableListOf<Processor<Biz.Message>>().apply {
                add(FilterProcessor(get()))
                if (ChatConfig.ENCRYPT) {
                    add(DecryptProcessor(get(), get()))
                }
                add(DatabaseProcessor(get(), get()))
            }
        }
        single(named("Signaling")) {
            listOf(
                GroupSignalProcessor(get(), get(), get()),
                RTCSignalProcessor(),
            )
        }
        single(named("ApiProto")) {
            listOf(
                ProtocolProcessor(
                    get(named("BizMessage")),
                    get(named("Signaling"))
                )
            )
        }
    }

    private fun Module.messageConfirmCallback() {
        single(named("ConfirmCallback")) {
            mutableListOf<OnMessageConfirmCallback>().apply {
                add(MessageSubscription)
            }
        }
    }

    override fun init(context: Context) {
        // 适配Android8.0通知栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var channelId = "chatMessage"
            var channelName = context.getString(R.string.core_channel_message)
            var importance = NotificationManager.IMPORTANCE_MAX
            var desc = context.getString(R.string.core_channel_message_desc)
            createNotificationChannel(context, channelId, channelName, importance, desc)
            channelId = "notification"
            channelName = context.getString(R.string.core_channel_notification)
            importance = NotificationManager.IMPORTANCE_LOW
            desc = context.getString(R.string.core_channel_notification_desc)
            createNotificationChannel(context, channelId, channelName, importance, desc)
            channelId = "rtcCall"
            channelName = context.getString(R.string.core_channel_rtc)
            importance = NotificationManager.IMPORTANCE_MAX
            desc = context.getString(R.string.core_channel_rtc_desc)
            createNotificationChannel(context, channelId, channelName, importance, desc)
        }
        // 启动阅后即焚待确认补发
        BurnConfirmScheduler.start()
    }


    /**
     * 创建通知渠道
     *
     * @param channelId     渠道标识id
     * @param channelName   渠道名
     * @param importance    通知优先级
     */
    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int,
        desc: String?
    ) {
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.description = desc
        context.notificationManager?.createNotificationChannel(channel)
    }

    private val serializeExclusionStrategy = object : ExclusionStrategy {
        override fun shouldSkipField(f: FieldAttributes?): Boolean {
            if (f?.declaredClass == ChatMessage::class.java) {
                if (f.name == "refMsg") {
                    val expose = f.getAnnotation(Expose::class.java)
                    return expose != null && !expose.serialize
                }
            }
            return false
        }

        override fun shouldSkipClass(clazz: Class<*>?) = false
    }

    private val deserializeExclusionStrategy = object : ExclusionStrategy {
        override fun shouldSkipField(f: FieldAttributes?): Boolean {
            if (f?.declaredClass == ChatMessage::class.java) {
                if (f.name == "refMsg") {
                    val expose = f.getAnnotation(Expose::class.java)
                    return expose != null && !expose.deserialize
                }
            }
            return false
        }

        override fun shouldSkipClass(clazz: Class<*>?) = false
    }

    private fun createRetrofit(client: OkHttpClient, gson: Gson, url: String): Retrofit {
        return Retrofit.Builder()
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .baseUrl(url)
            .build()
    }
    private fun createOkHttpClient(manager: RetrofitUrlManager?, sslFactory: SSLSocketFactory?,
                                   trustManager: X509TrustManager?, vararg interceptor: Interceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(20L, TimeUnit.SECONDS)
            .writeTimeout(20L, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newBuilder = originalRequest.newBuilder()
                newBuilder
                    .header("AppType", "TPOS")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Fzm-Request-Source", "wallet")
                    .header("FZM-REQUEST-OS", "android")
                    .header("FZM-PLATFORM-ID", "1")
                    .header("X-App-Id", "android_txchatapp")
                    .header("X-App-Token", "a3db3f9afa9c8f0ae0b189276f33c874")
                    .method(originalRequest.method, originalRequest.body)
                chain.proceed(newBuilder.build())
            }
            .addSSLSocketFactory(sslFactory, trustManager)
            .addInterceptors(*interceptor)
            .addChangeUrlInterceptor(manager)
        if (Arch.debug) {
            val loggingInterceptor = HttpLoggingInterceptor()
            loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
            builder.addNetworkInterceptor(loggingInterceptor)
        }
        return builder.build()
    }

    /**
     * 从下发的服务器列表里挑出「当前选中的聊天服务器」，只返回一个地址。
     *
     * 全量建连会让每个服务器各自无限重连（耗电、刷日志，不可达的节点会一直“第 N 次重连”）。
     * 选中地址来自 [AppPreference.CHAT_URL]，未选择或不在下发列表里时回退到列表第一个。
     */
    private fun pickSelectedServer(servers: List<Server>): List<String> {
        val addresses = servers.map { it.address }
        if (addresses.isEmpty()) return emptyList()
        val selected = AppPreference.CHAT_URL
        val target = if (selected.isEmpty()) {
            null
        } else {
            addresses.firstOrNull { it.urlKey() == selected.urlKey() }
        }
        return listOf(target ?: addresses.first())
    }

}