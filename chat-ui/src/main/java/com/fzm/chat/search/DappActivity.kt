package com.fzm.chat.search

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.core.utils.MMkvUtil
import com.fzm.chat.R
import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.data.UserPreference
import com.fzm.chat.biz.customerservice.AiCustomerService
import com.fzm.chat.databinding.ActivityDappBinding
import com.fzm.chat.databinding.DialogDappBottomBinding
import com.fzm.chat.databinding.DialogPwdBinding
import com.fzm.chat.databinding.MetaApproveBinding
import com.fzm.chat.databinding.MetaSignBinding
import com.fzm.chat.biz.utils.normalizeWebUrl
import com.fzm.chat.biz.webview.TanxinHost
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.search.meta.WalletSigner
import com.fzm.chat.search.meta.Web3Interface
import com.fzm.chat.search.meta.Web3InterfaceListener
import com.fzm.chat.search.web3.Address
import com.fzm.chat.search.web3.Constants
import com.fzm.chat.search.web3.Constants.Companion.FEE_CUSTOM_POSITION
import com.fzm.chat.search.web3.CreateTran
import com.fzm.chat.search.web3.DGear
import com.fzm.chat.search.web3.GoWallet
import com.fzm.chat.search.web3.GoWallet.Companion.CHAIN_NET
import com.fzm.chat.search.web3.JsListener
import com.fzm.chat.search.web3.JsWCApi
import com.fzm.chat.search.web3.URLLoadInterface
import com.fzm.chat.search.web3.Web3Call
import com.fzm.chat.search.web3.Web3Transaction
import com.fzm.chat.search.web3.Web3ViewClient
import com.fzm.chat.search.web3.toPlainStr
import com.fzm.chat.utils.ClipboardUtils
import com.fzm.chat.utils.PreferencesUtils
import com.fzm.chat.vm.BackupViewModel
import com.fzm.wallet.sdk.MnemonicManager
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.wallet.sdk.net.walletQualifier
import com.fzm.wallet.sdk.repo.WalletRepository
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.jeremyliao.liveeventbus.LiveEventBus
import com.permissionx.guolindev.PermissionX
import com.zjy.architecture.di.rootScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jetbrains.anko.toast
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.getViewModel
import org.litepal.LitePal
import org.litepal.extension.findFirst
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.crypto.StructuredDataEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import wallet.core.jni.Curve
import wallet.core.jni.Hash
import wallet.core.jni.PrivateKey
import walletapi.Walletapi
import wendu.dsbridge.DWebView
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.concurrent.thread
import kotlin.math.pow


@Route(path = MainModule.APP_DAPP)
class DappActivity : AppCompatActivity() {

    // 当前链：只支持 2999(BTY) 和 56(BSC)，默认 2999
    private var currentChainId: String = "2999"

    val WEB3_BNB_NODE = "https://bsc.publicnode.com"
    val WEB3_BTY_NODE = "https://mainnet.bityuan.com/eth"
    private var currentRpcUrl: String = ""

    private val viewModel by lazy { getViewModel<BackupViewModel>() }
    private val binding by lazy { ActivityDappBinding.inflate(layoutInflater) }
    private val va9 = 10.0.pow(9.0)
    private val va18 = 10.0.pow(18.0)

    val preference: UserPreference
        get() = UserPreference.getInstance()

    @JvmField
    @Autowired
    var name: String? = null

    @JvmField
    @Autowired
    var icon: String? = null

    @JvmField
    @Autowired(name = MainModule.PARAM_URL)
    var url: String? = null


    private var address = Address.EMPTY
    private var nodeUrl = GoWallet.WEB3_BNB
    private var chainId: Long = GoWallet.CHAIN_ID_BTY_L

    private var feePosition = 2

    //private var feePosition = 0
    private lateinit var cGas: BigInteger

    //原始gas
    private lateinit var origGas: BigInteger
    private lateinit var origGasPirce: BigInteger
    private lateinit var cGasPrice: BigInteger
    private var dGas: Double = 0.0
    private var tvFee: TextView? = null
    private var tvWCFee: TextView? = null
    private var tvLevel: TextView? = null
    private var chainName: String? = ""
    private var currentAddress = ""

    /** AI 客服 H5（自带返回键，打开时隐藏标题栏避免重复） */
    /** 判断当前是否 AI 客服页（隐藏标题栏） */
    private fun isAiCustomerServicePage(targetUrl: String?): Boolean =
        targetUrl?.contains(AiCustomerService.URL, ignoreCase = true) == true

    //private val walletRepository by rootScope.inject<WalletRepository>()

    private val walletRepository by lazy { rootScope.get<WalletRepository>(walletQualifier) }
    private val loading by lazy {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_loading, null)
        return@lazy AlertDialog.Builder(this).setView(view).create().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initData()
        ARouter.getInstance().inject(this)
        initObserve()
        val targetUrl = resolveTargetUrl()
        // AI 客服 H5 自带返回键：渲染前先隐藏标题栏，避免先出现再消失的闪烁
        if (isAiCustomerServicePage(targetUrl)) {
            binding.toolbar.visibility = View.GONE
        }
        doBar()
        initWebView()
        targetUrl?.let {
            binding.webDapp.loadUrl(it)
        }
        binding.tvToolbar.text = name ?: ""
        initLikeData()
    }

    fun initLikeData() {
        val saved = PreferencesUtils.getString(this, LIKE_URL_KEY)
        if (!TextUtils.isEmpty(saved)) {
            try {
                // Try new format: List<LikeDappItem>
                val items = Gson().fromJson<List<LikeDappItem>>(
                    saved,
                    object : TypeToken<List<LikeDappItem>>() {}.type
                )
                if (items != null && items.isNotEmpty() && items[0].url.isNotEmpty()) {
                    mUrlList.clear()
                    mUrlList.addAll(items)
                    return
                }
            } catch (_: Exception) { }
            try {
                // Fallback: old format List<String>
                val oldList = Gson().fromJson<List<String>>(
                    saved,
                    object : TypeToken<List<String>>() {}.type
                )
                if (oldList != null) {
                    mUrlList.clear()
                    mUrlList.addAll(oldList.map { LikeDappItem("", it, "") })
                }
            } catch (_: Exception) { }
        }
    }

    private fun initData() {
        currentChainId = "2999"
        currentRpcUrl = if (currentChainId == "56") WEB3_BNB_NODE else WEB3_BTY_NODE
    }

    private fun resolveTargetUrl(): String? {
        val raw = url?.takeIf { it.isNotBlank() }
            ?: intent?.getStringExtra(MainModule.PARAM_URL)
        return raw?.let { normalizeWebUrl(it) }?.takeIf { it.isNotBlank() }
    }

    private fun initObserve() {
        LiveEventBus.get<DGear>(GoWallet.LIVE_KEY_FEE).observe(this, Observer { dGear ->
            feePosition = dGear.position
            cGas = dGear.gas
            cGasPrice = dGear.gasPrice
            showGasUI(cGasPrice.toLong(), cGas.toLong(), chainName)
            setLevel(tvLevel)
            //cache
            Constants.setGear(dGear)
        })
    }

    private fun setLevel(tvLevel: TextView?) {
        val level = when (feePosition) {
            0 -> getString(R.string.high_str)
            1 -> getString(R.string.standard_str)
            2 -> getString(R.string.low_str)
            FEE_CUSTOM_POSITION -> getString(R.string.custom_str)
            else -> getString(R.string.low_str)
        }
        tvLevel?.text = level
    }


    private fun configChainNet() {
        val addr = GoWallet.getChain("BNB")?.address
        addr?.let {
            currentAddress = it
            address = Address(it)
            nodeUrl = GoWallet.WEB3_BNB
            chainId = GoWallet.CHAIN_ID_BNB_L
        }
    }

    private fun doBar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }
        // 将返回箭头的颜色设置为黑色
        binding.toolbar.navigationIcon?.setTint(Color.BLACK)
        //val view = (findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        //view.fitsSystemWindows = true
        //StatusBarUtil.StatusBarLightMode(this)

        binding.ivMenu.setOnClickListener {
            showBottomDialog()
        }
    }

    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        binding.toolbar.title = ""
    }


    override fun onBackPressed() {
        if (binding.webDapp.canGoBack()) {
            binding.webDapp.goBack()
        } else {
            super.onBackPressed()
        }

    }


    private fun initWebView() {
        //DWebView.setWebContentsDebuggingEnabled(BuildConfiG.DEBUG)
        binding.webDapp.addJavascriptInterface(JsWCApi(binding.webDapp, this, jsListener), "alpha")
        binding.webDapp.addJavascriptInterface(
            Web3Interface(
                this, binding.webDapp, web3InterfaceListener
            ), "AndroidHost"
        )
        binding.webDapp.settings.javaScriptEnabled = true
        binding.webDapp.settings.domStorageEnabled = true
        //解决http图片不显示
        binding.webDapp.settings.blockNetworkImage = false
        val userAgentString = binding.webDapp.settings.userAgentString
        val resultAgent = "$userAgentString;wallet;1.0"
        binding.webDapp.settings.userAgentString = resultAgent
        //https://www.cnblogs.com/ufreedom/p/4229590.html
        //禁用_blank打开新窗口，解决打开外部超链接失效问题
        binding.webDapp.settings.setSupportMultipleWindows(false)
        // 谈信 × H5 通用宿主对象（window.__tanxinHost），必须在 loadUrl 之前挂好
        TanxinHost.install(binding.webDapp, activityProvider = { this })
        setupWeb3()

    }


    /*   android 5.0(含) 以上开启图片选择（原生）
       可以自己改图片选择框架。*/

    private fun onenFileChooseImpleForAndroid(filePathCallback: ValueCallback<Array<Uri>>) {
        mUploadMessageForAndroid5 = filePathCallback
        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
        contentSelectionIntent.type = "image/*"
        val chooserIntent = Intent(Intent.ACTION_CHOOSER)
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "Image Chooser")
        startActivityForResult(chooserIntent, FILE_CHOOSER_RESULT_CODE_FOR_ANDROID_5)
    }

    private lateinit var web3ViewClient: Web3ViewClient
    private fun setupWeb3() {
        try {
            web3ViewClient = Web3ViewClient(this)
            binding.webDapp.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    binding.progressWeb.progress = newProgress
                }


                override fun onShowFileChooser(
                    webView: WebView?,
                    uploadMsg: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    onenFileChooseImpleForAndroid(uploadMsg);

                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    // 摄像头/麦克风（WebRTC getUserMedia）：先确保运行时权限已授予，再放行给 H5
                    val needMedia = request.resources.any {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    if (needMedia) {
                        PermissionX.init(this@DappActivity)
                            .permissions(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                            .request { allGranted, _, _ ->
                                if (allGranted) {
                                    request.grant(request.resources)
                                } else {
                                    request.deny()
                                }
                            }
                    } else {
                        request.grant(request.resources)
                    }
                }
            }



            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                binding.webDapp.webViewClient = object : WebViewClient() {
                    private var loadInterface: URLLoadInterface? = null
                    private var loadingError = false

                    //跳转新的dapp 导致连接不上的问题
                    private var redirect = false

                    val dWebView: DWebView = binding.webDapp
                    val progressBar: ProgressBar = binding.progressWeb
                    val internalClient: Web3ViewClient = web3ViewClient
                    val externalClient: WebViewClient = binding.webDapp.webViewClient

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // 谈信 × H5 通用宿主对象：onPageStarted 注入（页面脚本之前）
                        TanxinHost.inject(view)
                        configChainNet()
                        chainName = GoWallet.CHAIN_ID_MAPS_L[chainId]
                        web3ViewClient.jsInjectorClient.chainId = chainId
                        web3ViewClient.jsInjectorClient.rpcUrl = nodeUrl
                        web3ViewClient.jsInjectorClient.walletAddress = address
                        dWebView.clearCache(true)
                        if (!redirect) {
                            // 优先注入EIP-1193 Provider（Object.defineProperty防止被覆盖）
                            injectWeb3Provider(view)
                            internalClient.let {
                                view.evaluateJavascript(it.getProviderString(view), null)
                                view.evaluateJavascript(it.getInitString(view), null)
                                it.resetInject()
                            }

                        }
                        redirect = false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)

                        progressBar.visibility = View.GONE
                        if (!redirect && !loadingError) {
                            if (loadInterface != null) {
                                loadInterface!!.onWebpageLoaded(url, view.title)
                            }
                        } else if (!loadingError && loadInterface != null) {
                            loadInterface!!.onWebpageLoadComplete()
                        }
                        redirect = false
                        loadingError = false
                        // onPageFinished 兜底注入
                        TanxinHost.inject(view)
                        injectWeb3Provider(view)
                    }

                    // API 23+: 页面内容首次可见时注入（早于onPageFinished）
                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        super.onPageCommitVisible(view, url)
                        TanxinHost.inject(view)
                        injectWeb3Provider(view)
                    }

                    private fun injectWeb3Provider(view: WebView) {
                        // 读取 assets 文件夹下的 provider.js 并执行
                        try {
                            val `is` = assets.open("js/provider.js")
                            val buffer = ByteArray(`is`.available())
                            `is`.read(buffer)
                            `is`.close()
                            val script = String(buffer)
                            view.evaluateJavascript(script, null)
                            // provider.js 里 chainId/networkVersion 默认 null，页面加载时注入当前链真实值
                            injectChainState(view)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        //redirect = true
                        return (externalClient!!.shouldOverrideUrlLoading(
                            view, url
                        ) || internalClient.shouldOverrideUrlLoading(view, url))
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return internalClient.shouldInterceptRequest(view, request)
                            ?: externalClient?.shouldInterceptRequest(view, request)
                            ?: super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                    ) {
                        loadingError = true
                        externalClient?.onReceivedError(view, request, error)
                    }

                    override fun onReceivedSslError(
                        view: WebView?, handler: SslErrorHandler, error: SslError?
                    ) {
                        // 内网 IP / 自签名证书默认会被 WebView 拦截导致白屏，这里放行以正常加载
                        handler.proceed()
                    }

                    @RequiresApi(api = Build.VERSION_CODES.N)
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean {
                        //redirect = true
                        return (externalClient.shouldOverrideUrlLoading(
                            view, request
                        ) || internalClient.shouldOverrideUrlLoading(view, request))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    var mUploadMessageForAndroid5: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_RESULT_CODE_FOR_ANDROID_5 = 2
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        val result = if (intent == null || resultCode !== RESULT_OK) null else intent.data
        when (requestCode) {

            FILE_CHOOSER_RESULT_CODE_FOR_ANDROID_5 -> {
                if (null == mUploadMessageForAndroid5) return
                if (result != null) {
                    mUploadMessageForAndroid5?.onReceiveValue(arrayOf<Uri>(result))
                } else {
                    mUploadMessageForAndroid5?.onReceiveValue(arrayOf<Uri>())
                }
                mUploadMessageForAndroid5 = null
            }
        }

    }


    //---------------------------------------web3-------------------------------------------


    //-------------------------web3 listener--------------------------------
    private val JS_CALLBACK = "AlphaWallet.executeCallback(%1\$s, null, %2\$s)"
    private val JS_CALLBACK_ON = "AlphaWallet.executeCallback(%1\$s, null, \"%2\$s\")"
    private val JS_CALLBACK_ON_FAILURE = "AlphaWallet.executeCallback(%1\$s, \"%2\$s\", null)"

    private val JS_PROTOCOL_EXPR_ON_SUCCESSFUL = "AlphaWallet.executeCallback(%1\$s, null, %2\$s)"
    private val JS_CANCELLED = "cancelled"
    private val JS_FAIL = "Fail"

    var gasPrice = 0L
    var count = 0L
    private var payDialog: AlertDialog? = null
    private var metaPayDialog: AlertDialog? = null
    private var metaApproveDialog: AlertDialog? = null
    private var messageDialog: AlertDialog? = null

    private val jsListener = object : JsListener {
        override fun onRequestAccounts(callbackId: Long) {
            try {
                val callback = String.format(
                    JS_CALLBACK, callbackId, "[\"$address\"]"
                )

                binding.webDapp.evaluateJavascript(callback) { message: String? ->
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }


        override fun onEthCall(call: Web3Call) {
            try {
                lifecycleScope.launch(Dispatchers.IO) {
                    val httpService = HttpService(nodeUrl)
                    val web3j = Web3j.build(httpService)
                    val tran = Transaction.createFunctionCallTransaction(
                        address.toString(),
                        null,
                        null,
                        call.gasLimit,
                        call.to.toString(),
                        call.value,
                        call.payload
                    )
                    var value: String? = ""
                    try {
                        value = web3j?.ethCall(tran, call.blockParam)?.send()?.value
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            //优化报错提示，去掉自动刷新
                            //longToast("${getString(R.string.basic_error_all)}  $e")
                            //toast("${getString(R.string.tp_error)}")
                            dis()
                            //binding.webDapp.reload()
                            e.printStackTrace()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val callback: String =
                            String.format(JS_CALLBACK_ON, call.leafPosition, value)
                        //All WebView methods must be called on the same thread
                        //所以都放在主线程
                        binding.webDapp.evaluateJavascript(callback) { message: String? ->
                            //Timber.d(message)
                        }
                    }

                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }


        override fun onSignTransaction(transaction: Web3Transaction?) {
            try {
                lifecycleScope.launch(Dispatchers.Main) {
                    val view = LayoutInflater.from(this@DappActivity)
                        .inflate(R.layout.fragment_session_request, null)
                    val ivDappIcon = view.findViewById<ImageView>(R.id.iv_dapp_icon)
                    val tvDappName = view.findViewById<TextView>(R.id.tv_dapp_name)
                    val tvDappUrl = view.findViewById<TextView>(R.id.tv_dapp_url)
                    ivDappIcon.visibility = View.GONE
                    val tvPayMsg = view.findViewById<TextView>(R.id.tv_pay_msg)
                    val tvValue = view.findViewById<TextView>(R.id.tv_value)
                    val tvOutAddress = view.findViewById<TextView>(R.id.tv_out_address)
                    val tvInAddress = view.findViewById<TextView>(R.id.tv_in_address)
                    tvFee = view.findViewById<TextView>(R.id.tv_fee)
                    tvWCFee = view.findViewById<TextView>(R.id.tv_wc_fee)
                    tvLevel = view.findViewById<TextView>(R.id.tv_level)
                    val tvCancel = view.findViewById<TextView>(R.id.tv_cancel)
                    val btnNext = view.findViewById<Button>(R.id.btn_next)
                    val llSetFee = view.findViewById<LinearLayout>(R.id.ll_set_fee)
                    transaction?.let { tran ->
                        try {
                            val dGear = Constants.getGear()
                            if (dGear != null) {
                                feePosition = dGear.position
                                cGas = dGear.gas
                            } else {
                                cGas = tran.gasLimit
                            }

                            setLevel(tvLevel)
                            origGas = tran.gasLimit
                            // 设置默认 gasPrice 作为兜底，防止网络请求未完成时 gotoSetFee() 无反应
                            if (!::origGasPirce.isInitialized) {
                                origGasPirce = BigInteger.valueOf(5_000_000_000L)
                            }


                            var pValue = "0"
                            val valStr = tran.value.toString()
                            if (valStr != "0") {
                                val subvalStr = valStr.substring(0, valStr.length - 10)
                                val va8 = 10.0.pow(8.0)
                                pValue = "${subvalStr.toLong() / va8}".toPlainStr(8)
                            }
                            val netName = GoWallet.NET_MAPS[chainId]
                            chainName = GoWallet.CHAIN_ID_MAPS_L[chainId]
                            val addrChain = GoWallet.CHAIN_ID_MAPS_ADDR[chainId]
                            val addr = addrChain?.let { GoWallet.getChain(it)?.address }
                            addr?.let {
                                currentAddress = it
                                address = Address(it)
                                nodeUrl = GoWallet.getWeb3UrlL(chainId)
                            }


                            tvDappName.text = "$netName"
                            tvDappUrl.text = url
                            if (chainName == "BNB") {
                                if (tran.recipient == Address.EMPTY) {
                                    //为空是bnb转账
                                    tvValue.text = "$pValue $chainName"
                                } else {
                                    tvValue.text = "$pValue USDT"
                                }
                            } else {
                                tvValue.text = "$pValue $chainName"
                            }

                            tvOutAddress.text = address.toString()
                            tvInAddress.text = tran.recipient.toString()
                            tvPayMsg.text = "$chainName ${getString(R.string.home_transfer)}"

                            if (chainName == "BTY") {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val gasPriceResult = walletRepository.getGasPrice()
                                        val countResult =
                                            walletRepository.getTransactionCount(address.toString())
                                        withContext(Dispatchers.Main) {
                                            if (gasPriceResult.isSucceed()) {
                                                gasPriceResult.data()?.let {
                                                    gasPrice = it.substringAfter("0x").toLong(16)
                                                    origGasPirce = gasPrice.toBigInteger()
                                                }
                                            }
                                            if (countResult.isSucceed()) {
                                                countResult.data()?.let {
                                                    count = it.substringAfter("0x").toLong(16)
                                                }
                                            }



                                            if (dGear == null) {
                                                showGasUI(
                                                    gasPrice,
                                                    transaction.gasLimit.toLong(),
                                                    chainName
                                                )
                                            } else {
                                                showGasUI(
                                                    dGear.gasPrice.toLong(),
                                                    dGear.gas.toLong(),
                                                    chainName
                                                )
                                            }

                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                }
                            } else {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val web3Url = GoWallet.getWeb3UrlL(chainId)
                                        val web3j = Web3j.build(HttpService(web3Url))
                                        val gasPriceResult = web3j.ethGasPrice().send()
                                        val countResult = web3j.ethGetTransactionCount(
                                            address.toString(), DefaultBlockParameterName.LATEST
                                        ).send()
                                        withContext(Dispatchers.Main) {
                                            gasPrice = gasPriceResult.gasPrice.toLong()
                                            origGasPirce = gasPriceResult.gasPrice
                                            count = countResult.transactionCount.toLong()
                                            showGasUI(
                                                gasPrice, transaction.gasLimit.toLong(), chainName
                                            )
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                }
                            }


                            payDialog =
                                AlertDialog.Builder(this@DappActivity).setView(view).create()
                                    .apply {
                                        window?.setBackgroundDrawableResource(android.R.color.transparent)
                                    }
                            payDialog?.setCancelable(false)
                            payDialog?.show()

                            tvCancel.setOnClickListener {
                                feePosition = 2
                                payDialog?.dismiss()
                                val callback: String = String.format(
                                    JS_CALLBACK_ON_FAILURE, tran.leafPosition, JS_CANCELLED
                                )
                                binding.webDapp.evaluateJavascript(callback) { value: String? ->
                                }
                            }
                            btnNext.setOnClickListener {
                                try {
                                    if (customChain()) {
                                        if (dGas > 0.1) {
                                            toast(getString(R.string.tip_fee_high))
                                            return@setOnClickListener
                                        }
                                        if (!::cGasPrice.isInitialized || !::cGas.isInitialized) {
                                            toast(getString(R.string.tip_init_fee))
                                            return@setOnClickListener
                                        }
                                    }

                                    //普通转账input64就传null
                                    var input64: String? = null
                                    tran.payload?.let { data ->
                                        val input = data.substringAfter("0x")
                                        input64 = Base64.encodeToString(
                                            Walletapi.hexTobyte(input), Base64.DEFAULT
                                        )
                                    }

                                    val createTran = CreateTran(
                                        address.toString(),
                                        cGas,
                                        cGasPrice,
                                        input64,
                                        count,
                                        tran.recipient.toString(),
                                        tran.value,
                                        tran.leafPosition
                                    )

                                    lifecycleScope.launch(Dispatchers.IO) {
                                        withContext(Dispatchers.Main) {
                                            val bindingDialog =
                                                DialogPwdBinding.inflate(layoutInflater)
                                            val dialog = AlertDialog.Builder(this@DappActivity)
                                                .setView(bindingDialog.root).create().apply {
                                                    window?.setBackgroundDrawableResource(
                                                        android.R.color.transparent
                                                    )
                                                    show()
                                                }
                                            bindingDialog.ivClose.setOnClickListener {
                                                dialog.dismiss()
                                            }
                                            bindingDialog.btnOk.setOnClickListener {
                                                val password = bindingDialog.etInput.text.toString()
                                                if (password.isEmpty()) {
                                                    toast(getString(R.string.my_wallet_password_tips))
                                                    return@setOnClickListener
                                                }
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val mnem =
                                                        MnemonicManager.getMnemonicWords(password)
                                                    if (mnem.isNotEmpty()) {
                                                        dialog.dismiss()
                                                        sendTrans2(mnem, createTran)

                                                    } else {
                                                        withContext(Dispatchers.Main) {
                                                            toast("密码错误")
                                                        }

                                                    }

                                                }

                                            }
                                        }


                                    }


                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }



                            // 确保费用等级文本已设置
                            setLevel(tvLevel)

                            llSetFee.setOnClickListener {
                                gotoSetFee()
                            }
                            tvWCFee?.setOnClickListener {
                                gotoSetFee()
                            }
                            //
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        override fun onSignPersonalMessage(callbackId: Int, data: String) {
            doMessage(callbackId, data)
        }

        override fun onSignMessage(callbackId: Int, data: String) {
            //同理onSignPersonalMessage
            doMessage(callbackId, data)
        }


        override fun walletSwitchEthereumChain(callbackId: Int, data: String?) {
            val callback: String = String.format(
                JS_PROTOCOL_EXPR_ON_SUCCESSFUL, callbackId, null
            )
            val json = JSONObject(data)
            val cid = json.getString("chainId")
            val chainType = json.getString("chainType")
            val switchId = cid.substringAfter("0x").toLong(16)
            Log.v("pro", "switchId  == $switchId")

            /*binding.webDapp.evaluateJavascript(callback) { value: String? ->
            }*/
            MMkvUtil.encode(CHAIN_NET, switchId)

            binding.webDapp.reload()


        }

        override fun walletAddEthereumChain(callbackId: Int, data: String?) {
            val callback: String = String.format(
                JS_PROTOCOL_EXPR_ON_SUCCESSFUL, callbackId, null
            )
            binding.webDapp.evaluateJavascript(callback) { value: String? ->
            }
        }

    }

    //meta
    private fun sendToJs(id: String, result: String) {
        Log.v("tag","id = $id, result = $result")
        binding.webDapp.post {
            // 调用 JS 里的 _onResponse
            binding.webDapp.evaluateJavascript(
                "window.ethereum._onResponse('$id', $result, null)", null
            )
        }
    }

    /**
     * 将当前链状态注入页面 provider（chainId=十六进制 / networkVersion=十进制）
     *
     * provider.js 初始 chainId/networkVersion 为 null；页面加载与"已是当前链"的切换请求时
     * 都注入一次，保证 dApp 读到的值始终与 currentChainId 一致（只支持 2999/56）。
     */
    private fun injectChainState(webView: WebView) {
        val hexId = "0x" + (currentChainId.toLongOrNull() ?: 2999L).toString(16)
        // 切链回调在 JS 桥线程，evaluateJavascript 必须 post 到 WebView 所在线程
        webView.post {
            webView.evaluateJavascript(
                "if (window.ethereum) { window.ethereum.chainId = '$hexId'; window.ethereum.networkVersion = '$currentChainId'; }",
                null
            )
        }
    }

    private fun sendTranToJs(id: String, result: String) {
        binding.webDapp.post {
            // 如果 result 本身已经是 "0x..." 这种带双引号的 JSON 串，直接拼
            // 如果 result 是纯地址 0x...，则需要包裹引号
            val formattedResult = if (result.startsWith("\"")) result else "'$result'"

            val jsCode = "window.ethereum._onResponse('$id', $formattedResult, null)"
            binding.webDapp.evaluateJavascript(jsCode, null)
        }
    }


    private fun sendToJsError(id: String, message: String) {
        binding.webDapp.post {
            val errorJson = "{\"code\": -32603, \"message\": \"$message\"}"
            binding.webDapp.evaluateJavascript(
                "window.ethereum._onResponse('$id', null, $errorJson)", null
            )
        }
    }

    private fun sendErrorToJs(id: String, code: Int, message: String) {
        // 构造符合 EIP-1193 的错误对象给 _onResponse 的第三个参数
        val errorJson = "{\"code\": $code, \"message\": \"$message\"}"
        val js = "window.ethereum._onResponse('$id', null, $errorJson);"
        binding.webDapp.post { binding.webDapp.evaluateJavascript(js, null) }
    }

    private val web3InterfaceListener = object : Web3InterfaceListener {
        override fun ethAccounts(id: String) {
            try {
                val address = "[\"$address\"]"
                // 调用 JS 里的 _onResponse
                sendToJs(id, address)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun ethChainId(id: String) {
            try {
                // 与 currentChainId 同源换算：56 -> 0x38、2999 -> 0xbb7（不再硬编码）
                val chainId = "0x" + (currentChainId.toLongOrNull() ?: 2999L).toString(16)
                // 注意这里要加外部引号 "\"$chainId\""
                sendToJs(id, "\"$chainId\"")
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        override fun netVersion(id: String) {
            Log.v("wawawa", "currentChainId = $currentChainId")
            sendToJs(id, "\"$currentChainId\"")
        }

        override fun personalSign(id: String, params: JSONArray) {
            val bindingDialog = DialogPwdBinding.inflate(layoutInflater)
            val dialog =
                AlertDialog.Builder(this@DappActivity).setView(bindingDialog.root).create()
                    .apply {
                        window?.setBackgroundDrawableResource(android.R.color.transparent)
                        show()
                    }
            bindingDialog.ivClose.setOnClickListener {
                dialog.dismiss()
            }
            bindingDialog.btnOk.setOnClickListener {
                val password = bindingDialog.etInput.text.toString()
                if (password.isEmpty()) {
                    toast(getString(R.string.my_wallet_password_tips))
                    return@setOnClickListener
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val mnem = MnemonicManager.getMnemonicWords(password)
                    if (mnem.isNotEmpty()) {
                        dialog.dismiss()
                        val privKey = GoWallet.getPrikey(
                            "ETH", mnem
                        )
                        // 2. 开启协程或异步线程进行签名和广播（不能在 UI 线程联网）
                        thread {
                            try {
                                val message = params.getString(0)
                                val signature = WalletSigner.signPersonalMessage(message, privKey)
                                sendToJs(id, "\"$signature\"")
                            } catch (e: Exception) {
                                // 返回用户拒绝或失败的错误
                                val errorJson =
                                    "{\"code\": 4001, \"message\": \"User rejected or transaction failed\"}"
                                binding.webDapp.post {
                                    binding.webDapp.evaluateJavascript(
                                        "window.ethereum._onResponse('$id', null, '$errorJson')",
                                        null
                                    )
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            toast("密码错误")
                        }

                    }

                }

            }


        }

        //是否无限授权
        fun isUnlimitedAmount(amountStr: String): Boolean {
            return try {
                val amount = BigInteger(amountStr)

                // 1. Permit2 标准的无限额度 (2^160 - 1)
                val permit2Max = BigInteger("2").pow(160).subtract(BigInteger.ONE)

                // 2. 传统的 ERC20 approve 无限额度 (2^256 - 1)
                // 对应十六进制 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
                val uint256Max = BigInteger("2").pow(256).subtract(BigInteger.ONE)

                // 3. 常见的“极大值”判断（超过一定阈值，例如 10^30）
                // 现实中很少有代币总量超过 10^30 (10^12 * 10^18)
                val veryLargeThreshold = BigInteger("10").pow(30)

                // 判断逻辑
                amount == permit2Max || amount == uint256Max || amount >= veryLargeThreshold
            } catch (e: Exception) {
                false
            }
        }

        override fun ethSignTypedDataV4(id: String, params: JSONArray) {
            try {
                //amount 的值非常夸张：1461501637330902918203684832716283019655932542975这是一个 Unlimited Approval (无限授权) 的典型数值（\(2^{160}-1\)）。
                //  安全提示： 用户提示：在钱包界面，你必须解析这个 JSON 并明确告诉用户：“你正在授权支出无限额度的 CAKE 令牌给 Spender (0xd9c5...)”。

                val approveBinding =
                    MetaApproveBinding.inflate(LayoutInflater.from(this@DappActivity))

                val address = params.getString(0) // 第一个参数是地址
                val jsonData = params.getString(1) // 第二个参数是结构化数据字符串
                // 解析顶层 JSON 对象
                val jsonObject = JSONObject(jsonData)
                // 提取 domain 对象
                val domainObject = jsonObject.getJSONObject("domain")

                // 提取 message 对象
                val messageObject = jsonObject.getJSONObject("message")

                // 从 domain 中获取 chainId 和 verifyingContract
                val chainId = domainObject.getString("chainId")
                val verifyingContract = domainObject.getString("verifyingContract")

                // 从 message -> details 中获取 amount
                val detailsObject = messageObject.getJSONObject("details")
                val amount = detailsObject.getString("amount")
                approveBinding.tvVerifyingContract.text = "合约地址：$verifyingContract"
                if (isUnlimitedAmount(amount)) {
                    approveBinding.tvAmount.text = "无限"
                } else {
                    approveBinding.tvAmount.text = "$amount"
                }
                val chainIdStr = if (chainId == "56") "BNB Smart Chain" else chainId
                approveBinding.tvChainid.text = "$chainIdStr"
                approveBinding.tvOutAddress.text = "$address"


                approveBinding.tvCancel.setOnClickListener {
                    metaApproveDialog?.dismiss()
                }
                approveBinding.btnNext.setOnClickListener {
                    metaApproveDialog?.dismiss()
                    val bindingDialog = DialogPwdBinding.inflate(layoutInflater)
                    val dialog =
                        AlertDialog.Builder(this@DappActivity).setView(bindingDialog.root).create()
                            .apply {
                                window?.setBackgroundDrawableResource(android.R.color.transparent)
                                show()
                            }
                    bindingDialog.ivClose.setOnClickListener {
                        dialog.dismiss()
                    }
                    bindingDialog.btnOk.setOnClickListener {
                        val password = bindingDialog.etInput.text.toString()
                        if (password.isEmpty()) {
                            toast(getString(R.string.my_wallet_password_tips))
                            return@setOnClickListener
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val mnem = MnemonicManager.getMnemonicWords(password)
                            if (mnem.isNotEmpty()) {
                                dialog.dismiss()
                                val privKey = GoWallet.getPrikey(
                                    "ETH", mnem
                                )
                                // 1. 使用 Web3j 的 StructuredDataEncoder 解析并编码数据
                                // 这个工具类会自动处理 EIP-712 的 DomainHash 和 MessageHash
                                val encoder = StructuredDataEncoder(jsonData)

                                // 2. 生成结构化数据的哈希值 (hashStructuredData)
                                val hash = encoder.hashStructuredData()

                                // 3. 使用私钥进行签名
                                val credentials = Credentials.create(privKey)
                                val signatureData =
                                    Sign.signMessage(hash, credentials.ecKeyPair, false)

                                // 4. 将签名结果组合成标准的十六进制字符串 (r + s + v)
                                val retval = StringBuilder("0x") // 必须以 0x 开头
                                retval.append(Numeric.toHexStringNoPrefix(signatureData.r))
                                retval.append(Numeric.toHexStringNoPrefix(signatureData.s))

                                // 2. 获取 v 的十六进制（带 0x）
                                val vWithPrefix = Numeric.toHexString(signatureData.v)

                                // 3. 去掉 v 的 0x 前缀
                                val vNoPrefix = vWithPrefix.replace("0x", "")

                                // 4. 关键：确保 v 是 2 位字符（如果是 "b" 则补成 "0b"）
                                if (vNoPrefix.length == 1) {
                                    retval.append("0")
                                }
                                retval.append(vNoPrefix)

                                val finalSignature = retval.toString()
                                Log.v("wawa", "finalSignature = $finalSignature")
                                // 此时 finalSignature 格式为 0x... (总长度 132 字符)
                                sendToJs(id, "\"$finalSignature\"")


                            } else {
                                withContext(Dispatchers.Main) {
                                    toast("密码错误")
                                }

                            }

                        }

                    }
                }

                metaApproveDialog =
                    AlertDialog.Builder(this@DappActivity).setView(approveBinding.root).create()
                        .apply {
                            window?.setBackgroundDrawableResource(android.R.color.transparent)
                        }
                metaApproveDialog?.setCancelable(false)
                metaApproveDialog?.show()


            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun extractTokenAddress(data: String): String? {
            return try {
                if (data.length >= 74) { // 0x + 8位函数选择器 + 64位参数
                    // 截取第一参数位置的地址 (去掉前面的 0 填充)
                    "0x" + data.substring(34, 74)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }


        override fun ethSendTransaction(id: String, params: JSONArray) {
            val signBinding =
                MetaSignBinding.inflate(LayoutInflater.from(this@DappActivity))
            // 1. 获取交易参数对象
            val txParams = params.getJSONObject(0)
            val to = txParams.getString("to")
            val data = txParams.optString("data", "0x")
            val value = txParams.optString("value", "0x0") // 十六进制字符串
            //理由： 绝大多数 DEX（PancakeSwap, Uniswap V2/V3）在复杂的路径换币时，消耗通常在 15w - 30w 之间。设置 50w 可以覆盖 99% 的情况，且多余的 Gas 在交易完成后会自动退回给用户。
            val gasLimit = txParams.optString("gas", "0xF4240") // 默认 100w
            var gasLimitBI: BigInteger = org.web3j.utils.Numeric.toBigInt(gasLimit)
            //data中提取amount
            // 1. 提取金额部分（截取最后 64 位字符）
            // data 长度通常为 138 (0x + 8 + 64 + 64)
            val amountHex = if (data.length >= 138) {
                data.substring(data.length - 64)
            } else {
                "0"
            }

            // 2. 将十六进制转为大整数 (Wei 单位)
            val rawAmount = BigInteger(amountHex, 16)

            // 3. 转换为人类可读金额 (假设精度为 18 位)
            // 注意：如果是主网 USDT 精度是 6，但根据你之前提供的数据，你的链是 18
            val decimals = 18
            val divisor = BigDecimal.TEN.pow(decimals)
            val readableAmount = BigDecimal(rawAmount).divide(divisor).toPlainString()


            //授权和交易
            // 定义 ERC20 标准的 Method ID
            val METHOD_APPROVE = "0x095ea7b3"    // approve(address,uint256)
            val METHOD_TRANSFER = "0xa9059cbb"   // transfer(address,uint256)
            val METHOD_SET_APPROVAL_FOR_ALL = "0xa22cb465" // NFT 常用授权

            when {

                // 情况 B: 调用了 approve 函数 -> 授权行为
                data.startsWith(METHOD_APPROVE) -> {
                    signBinding.tvValue.text = "${getString(R.string.dapp_nonono)}"
                    //授权合约
                    signBinding.tvInValue.text = "${getString(R.string.dapp_approve)}"
                    //发起地址
                    signBinding.tvOutValue.text = "${getString(R.string.dapp_out)}"
                }

                // 情况 D: 调用了 transfer 函数 -> ERC20 代币转账
                data.startsWith(METHOD_TRANSFER) -> {
                    //"Token Transfer (代币转账)"
                    signBinding.tvInValue.text = "${getString(R.string.home_receipt_address)}"
                    signBinding.tvOutValue.text = "${getString(R.string.wc_pay_address)}"
                }
            }


            // 如果需要解析授权给了谁，可以截取 data
            if (data.startsWith(METHOD_APPROVE) && data.length >= 74) {
                // 授权对象地址在 data 的第 10 到 74 位（需补齐 0x）
                val spender = "0x" + data.substring(34, 74)
                println("授权对象: $spender")
            }

            // BSC 公共节点
            val web3j = Web3j.build(HttpService(currentRpcUrl))
// 1. 获取当前节点的 gasPrice
            val currentGasPrice = web3j.ethGasPrice().send().gasPrice

// 2. 增加 20% 缓冲区 (乘以 120，再除以 100)
            var gasPrice = currentGasPrice.multiply(BigInteger.valueOf(1000))
                .divide(BigInteger.valueOf(100))


            // 移除 '0x' 前缀
            val cleanHexValue = value.replace("0x", "")

            // 将十六进制转换为十进制 BigInteger
            val weiValueBI = BigInteger(cleanHexValue, 16)
            val weiValueBD = BigDecimal(weiValueBI)

            // 1 BNB = 10^18 Wei

            val weiPerBNB = BigDecimal(va18)

            // 计算 BNB 数量，保留 18 位小数并使用 toPlainString 避免科学计数法
            val bnbValueBD =
                weiValueBD.divide(weiPerBNB, 18, RoundingMode.HALF_UP).stripTrailingZeros()

            signBinding.tvOutAddress.text = address.toString()
            signBinding.tvInAddress.text = to

            try {
                if (data.isEmpty() || data == "0x") {
                    val symbol = if (currentChainId == "56") "BNB" else "BTY"
                    signBinding.tvValue.text = "$readableAmount$symbol"
                } else {
                    // 情况 B: 合约调用 (代币转账或 Swap)
                    // 从你的 data 中截取代币地址 (0xffE0...08d)
                    // 逻辑：data 里的地址通常在特定位置，这里我们根据你提供的例子提取
                    val contractAddress = extractTokenAddress(data) ?: to

                    // 1. 定义 symbol() 函数 (ERC-20 标准)
                    val function = Function(
                        "symbol",
                        emptyList(),
                        listOf(object : TypeReference<Utf8String>() {})
                    )

                    // 2. 编码函数调用数据
                    val encodedFunction = FunctionEncoder.encode(function)

                    // 3. 发送 eth_call 请求
                    val response = web3j.ethCall(
                        Transaction.createEthCallTransaction(
                            null,
                            contractAddress,
                            encodedFunction
                        ),
                        DefaultBlockParameterName.LATEST
                    ).send()

                    // 4. 解码返回结果
                    val results =
                        FunctionReturnDecoder.decode(response.value, function.outputParameters)
                    if (results.isNotEmpty()) {
                        val symbol = results[0].value.toString()
                        signBinding.tvValue.text = "$readableAmount$symbol"
                    }


                }


                // 1. 将输入转换为 BigDecimal
                val priceBD = BigDecimal(gasPrice)
                val limitBD = BigDecimal(gasLimitBI)

                // 2. 计算总费用（单位：Gwei）
                val totalWei = priceBD.multiply(limitBD)

                val divisor = BigDecimal(va18)
                val totalBNB =
                    totalWei.divide(divisor, 18, RoundingMode.HALF_UP).stripTrailingZeros()

                // 转换为 Gwei 方便在括号里显示 (除以 10^9)
                val gweiDivisor = BigDecimal(va9)
                val priceInGwei =
                    priceBD.divide(gweiDivisor, 8, RoundingMode.HALF_UP).stripTrailingZeros()

                // 4. 格式化输出给用户
                // toPlainString() 确保不使用科学计数法
                val gasName = if (currentChainId == "56") "BNB" else "BTY"
                val displayText =
                    "${totalBNB.toPlainString()} $gasName = GasLimit($gasLimitBI) * GasPrice(${priceInGwei.toPlainString()} GWEI)"
                signBinding.tvFee.text = "${totalBNB.toPlainString()} $gasName"
                signBinding.tvWcFee.text = displayText
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 网络费选择：设置类级变量供 gotoSetFee() 使用
            if (customChain()) {
                origGasPirce = gasPrice
                origGas = gasLimitBI
                chainName = if (currentChainId == "56") "BNB" else "BTY"
                tvFee = signBinding.tvFee
                tvWCFee = signBinding.tvWcFee
                tvLevel = signBinding.tvLevel
                signBinding.llSetFee.visibility = View.VISIBLE
                setLevel(signBinding.tvLevel)
                signBinding.llSetFee.setOnClickListener {
                    gotoSetFee()
                }
                signBinding.tvWcFee.setOnClickListener {
                    gotoSetFee()
                }
            }

            signBinding.tvCancel.setOnClickListener {
                metaPayDialog?.dismiss()
                sendErrorToJs(id, 4001, "User rejected the request")
            }
            signBinding.btnNext.setOnClickListener {
                metaPayDialog?.dismiss()
                val bindingDialog = DialogPwdBinding.inflate(layoutInflater)
                val dialog =
                    AlertDialog.Builder(this@DappActivity).setView(bindingDialog.root).create()
                        .apply {
                            window?.setBackgroundDrawableResource(android.R.color.transparent)
                            show()
                        }
                bindingDialog.ivClose.setOnClickListener {
                    dialog.dismiss()
                }
                bindingDialog.btnOk.setOnClickListener {
                    val password = bindingDialog.etInput.text.toString()
                    if (password.isEmpty()) {
                        toast(getString(R.string.my_wallet_password_tips))
                        return@setOnClickListener
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val mnem = MnemonicManager.getMnemonicWords(password)
                        if (mnem.isNotEmpty()) {
                            dialog.dismiss()
                            val privKey = GoWallet.getPrikey(
                                "ETH", mnem
                            )
                            // 2. 开启协程或异步线程进行签名和广播（不能在 UI 线程联网）
                            thread {
                                try {
                                    // 使用最新设置的 gasPrice 和 gasLimit（可能通过费用选择弹窗修改）
                                    val finalGasPrice = if (::cGasPrice.isInitialized) cGasPrice else gasPrice
                                    val finalGasLimit = if (::cGas.isInitialized) cGas else gasLimitBI
                                    // 使用 Web3j 发送交易并获取 Hash
                                    val txHash = WalletSigner.sendTransaction(
                                        to,
                                        data,
                                        valueHex = value,
                                        finalGasLimit,
                                        privKey,
                                        web3j,
                                        finalGasPrice,
                                        currentChainId.toLong()
                                    )

                                    // 3. 返回交易哈希给 DApp
                                    sendToJs(id, "\"$txHash\"")
                                } catch (e: Exception) {
                                    // 返回用户拒绝或失败的错误
                                    val errorJson =
                                        "{\"code\": 4001, \"message\": \"User rejected or transaction failed\"}"
                                    binding.webDapp.post {
                                        binding.webDapp.evaluateJavascript(
                                            "window.ethereum._onResponse('$id', null, '$errorJson')",
                                            null
                                        )
                                    }
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                toast("密码错误")
                            }

                        }

                    }

                }
            }

            metaPayDialog =
                AlertDialog.Builder(this@DappActivity).setView(signBinding.root).create()
                    .apply {
                        window?.setBackgroundDrawableResource(android.R.color.transparent)
                    }
            metaPayDialog?.setCancelable(false)


            metaPayDialog?.show()


        }

        private fun getSChainName(id: String): String {
            return when (id) {
                "56" -> "BNB Smart Chain"
                "2999" -> "BTY Chain"
                else -> ""
            }
        }

        override fun walletSwitchEthereumChain(id: String, params: JSONArray) {
            val targetHex = params.getJSONObject(0).getString("chainId")
            // 1. 将 16 进制转换为 10 进制字符串，方便匹配
            val decimalId = try {
                java.lang.Long.decode(targetHex).toString()
            } catch (e: Exception) {
                sendErrorToJs(id, 4200, "Invalid chain ID format")
                return
            }

            // 2. 严格限制只能切换到 56 和 2999
            if (decimalId != "56" && decimalId != "2999") {
                // 如果是其他链，告知 DApp 暂不支持（或返回 4902 让其尝试 addChain）
                sendErrorToJs(id, 4902, "Only BSC(56) and Chain(2999) are supported.")
                return
            }

            // 3. 如果已经是当前链，直接返回成功（顺带补一次状态注入，修正 dApp 侧可能残留的旧值）
            if (decimalId == currentChainId) {
                injectChainState(binding.webDapp)
                sendToJs(id, "null")
                return
            }

            // 4. 弹出对话框让用户确认（运行在 UI 线程）
            AlertDialog.Builder(this@DappActivity)
                .setTitle("切换网络")
                .setMessage(
                    "网站请求将网络从 ${getSChainName(currentChainId)} 切换至 ${
                        getSChainName(
                            decimalId
                        )
                    }"
                )
                .setPositiveButton("确认") { _, _ ->
                    // 执行切换逻辑
                    performSwitch(id, decimalId, targetHex)
                }
                .setNegativeButton("取消") { _, _ ->
                    // 4001 是 EIP 定义的“用户拒绝”错误码
                    sendErrorToJs(id, 4001, "User rejected the switch.")
                }
                .setCancelable(false)
                .show()
        }

        override fun walletAddEthereumChain(id: String, params: JSONArray) {
            try {
                val chainParams = params.getJSONObject(0)
                val targetHex = chainParams.optString("chainId", "")
                val decimalId = try {
                    java.lang.Long.decode(targetHex).toString()
                } catch (e: Exception) {
                    sendErrorToJs(id, 4200, "Invalid chain ID format")
                    return
                }

                // 只支持 BSC(56) 和 BTY(2999)
                if (decimalId == "56" || decimalId == "2999") {
                    // 已支持的链，直接返回成功
                    sendToJs(id, "null")
                } else {
                    sendErrorToJs(id, 4200, "Only BSC(56) and BTY(2999) are supported.")
                }
            } catch (e: Exception) {
                sendErrorToJs(id, 4200, "Failed to add chain: ${e.message}")
            }
        }


        // 假设这是你的 Web3InterfaceListener 实现类中的方法
        override fun ethGetTransactionReceipt(id: String, params: JSONArray) {
            val txHash = params.optString(0) // 获取 DApp 传来的交易哈希
            if (txHash.isNullOrEmpty()) {
                sendToJsError(id, "Invalid transaction hash")
                return
            }

            // 开启异步线程请求 RPC 节点
            thread {
                try {
                    // 1. 构造标准的 JSON-RPC 请求体
                    val rpcRequestBody = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", System.currentTimeMillis())
                        put("method", "eth_getTransactionReceipt")
                        put("params", JSONArray().put(txHash))
                    }

                    // 2. 发送请求到你的 RPC 节点 (例如：https://polygon-rpc.com)
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url(currentRpcUrl) // 必须和你的 eth_chainId 匹配
                        .post(
                            rpcRequestBody.toString()
                                .toRequestBody("application/json".toMediaType())
                        )
                        .build()

                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string()
                        if (response.isSuccessful && responseBody != null) {
                            val rpcResponse = JSONObject(responseBody)

                            // 3. 获取 result 部分
                            // 如果交易没出块，result 是 null (JSONObject.NULL)
                            // 如果交易已出块，result 是一个包含 status, blockNumber 等字段的对象
                            val result = rpcResponse.opt("result")

                            // 4. 返回给前端 JS
                            if (result == null || result == JSONObject.NULL) {
                                // 必须返回 null 字符串，DApp 会继续轮询
                                sendToJs(id, "null")
                            } else {
                                // 返回收据对象字符串，例如 {"status":"0x1", "blockNumber":"0x..."}
                                sendToJs(id, result.toString())
                            }
                        } else {
                            sendToJsError(id, "RPC Node Error")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("wawawa", "查询收据失败: ${e.message}")
                    sendToJsError(id, e.message ?: "Unknown Error")
                }
            }
        }


        private val client = OkHttpClient()
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 转发只读请求到 RPC 节点
         * @param id JS 传过来的随机字符串 ID
         * @param method RPC 方法名 (eth_call, eth_getBalance 等)
         * @param paramsString JS 传过来的 params 数组字符串 (例如 "[{...}, \"latest\"]")
         */
        override fun handleReadRpcCall(id: String, method: String, paramsString: String) {
            // 1. 确定当前应该请求哪个节点
            val rpcUrl = if (currentChainId == "56") WEB3_BNB_NODE else WEB3_BTY_NODE

            // 2. 构造符合 JSON-RPC 2.0 规范的请求体
            val jsonRequestBody = """
        {
            "jsonrpc": "2.0",
            "id": "$id",
            "method": "$method",
            "params": $paramsString
        }
    """.trimIndent()

            val request = Request.Builder()
                .url(rpcUrl)
                .post(jsonRequestBody.toRequestBody(JSON_TYPE))
                .build()

            // 3. 发送异步请求
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 网络错误回调给 JS
                    sendErrorToJs(id, -32000, "Network error: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            sendErrorToJs(id, response.code, "RPC Node error: ${response.message}")
                            return
                        }

                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            try {
                                val jsonResponse = JSONObject(responseBody)

                                // 从 RPC 响应中提取 result 或 error
                                if (jsonResponse.has("result")) {
                                    val result = jsonResponse.get("result")
                                    // 将结果传回 JS。如果是字符串要加引号，如果是对象/数组直接传
                                    val resultString =
                                        if (result is String) "\"$result\"" else result.toString()
                                    sendToJs(id, resultString)
                                } else if (jsonResponse.has("error")) {
                                    val error = jsonResponse.getJSONObject("error")
                                    sendErrorToJs(
                                        id,
                                        error.getInt("code"),
                                        error.getString("message")
                                    )
                                }
                            } catch (e: Exception) {
                                sendErrorToJs(id, -32603, "Internal JSON parse error")
                            }
                        }
                    }
                }
            })
        }


        private fun performSwitch(id: String, newDecimalId: String, newHexId: String) {
            // 1. 更新 Android 本地全局状态（确保后续逻辑能读取到新链）
            currentChainId = newDecimalId
            currentRpcUrl = if (newDecimalId == "56") WEB3_BNB_NODE else WEB3_BTY_NODE

            binding.webDapp.post {
                // 2. 先更新 Provider 内部的属性（同步操作），防止 DApp 在收到回调后立刻读取到旧属性
                binding.webDapp.evaluateJavascript(
                    """
            if (window.ethereum) {
                window.ethereum.chainId = '$newHexId';
                window.ethereum.networkVersion = '$newDecimalId';
                console.log('[Provider] Internal state updated to: $newHexId');
            }
        """.trimIndent(), null
                )

                // 3. 【核心】回复 DApp 发起的 switch 请求 (Resolve Promise)
                // 注意：这里的 null 不要加引号，确保 JS 接收到的是 null 对象
                val resolveResponse = "window.ethereum._onResponse('$id', null, null);"
                binding.webDapp.evaluateJavascript(resolveResponse, null)

                // 4. 【广播】触发标准事件通知 DApp（异步通知）
                // 很多 DApp 只有收到这两个事件才会停止对 eth_chainId 的死循环轮询
                val emitChainChanged = "window.ethereum.emit('chainChanged', '$newHexId');"
                val emitNetworkChanged = "window.ethereum.emit('networkChanged', '$newDecimalId');"

                binding.webDapp.evaluateJavascript(emitChainChanged, null)
                binding.webDapp.evaluateJavascript(emitNetworkChanged, null)

                Log.d("Web3Wallet", "Switch successful: ID $id -> $newHexId ($newDecimalId)")
            }
        }


        // 辅助方法：获取链名称
        private fun getChainName(id: String): String = if (id == "56") "BSC" else "Chain 2999"


    }


    private var msgPwdDialog: Dialog? = null
    private fun doMessage(callbackId: Int, data: String) {
        val view =
            LayoutInflater.from(this@DappActivity).inflate(R.layout.fragment_session_message, null)
        val tvDappName = view.findViewById<TextView>(R.id.tv_dapp_name)
        val tvDappUrl = view.findViewById<TextView>(R.id.tv_dapp_url)
        val tvMessage = view.findViewById<TextView>(R.id.tv_message)
        val tvAddress = view.findViewById<TextView>(R.id.tv_wallet_addr)
        val tvCancel = view.findViewById<TextView>(R.id.tv_cancel)
        val btnNext = view.findViewById<Button>(R.id.btn_next)


        val addrChain = GoWallet.CHAIN_ID_MAPS_ADDR[chainId]

        val addr = addrChain?.let { GoWallet.getChain(it)?.address }
        addr?.let {
            currentAddress = it
            address = Address(it)
            nodeUrl = GoWallet.getWeb3UrlL(chainId)
        }

        val netName = GoWallet.NET_MAPS[chainId]
        tvDappName.text = "$netName"
        tvDappUrl.text = url
        tvMessage.text = data
        tvAddress.text = address.toString()

        messageDialog = AlertDialog.Builder(this@DappActivity).setView(view).create().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        messageDialog?.setCancelable(false)
        messageDialog?.show()

        tvCancel.setOnClickListener {
            messageDialog?.dismiss()
            val callback: String = String.format(
                JS_CALLBACK_ON_FAILURE, callbackId, JS_CANCELLED
            )
            binding.webDapp.evaluateJavascript(callback) { value: String? ->
            }
        }
        btnNext.setOnClickListener {
            messageDialog?.dismiss()
            val mnem = preference.getMnemonicStringWithDefaultPwd() ?: ""

            if (mnem.isEmpty()) {
                val bindingDialog = DialogPwdBinding.inflate(layoutInflater)
                val dialog = AlertDialog.Builder(this).setView(bindingDialog.root).create().apply {
                    window?.setBackgroundDrawableResource(android.R.color.transparent)
                    show()
                }
                bindingDialog.ivClose.setOnClickListener {
                    dialog.dismiss()
                }
                bindingDialog.btnOk.setOnClickListener {
                    val password = bindingDialog.etInput.text.toString()
                    if (password.isEmpty()) {
                        toast(getString(R.string.my_wallet_password_tips))
                        return@setOnClickListener
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        val mnem = MnemonicManager.getMnemonicWords(password)
                        if (mnem.isNotEmpty()) {
                            dialog.dismiss()
                            sendMsgTrans2(mnem, callbackId, data)
                        } else {
                            withContext(Dispatchers.Main) {
                                toast("密码错误")
                            }

                        }

                    }

                }
            } else {
                sendMsgTrans2(mnem, callbackId, data)
            }

        }


    }


    private fun showMsgPWD(wallet: PWallet, callbackId: Int, data: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pwd, null)
        msgPwdDialog = AlertDialog.Builder(this).setView(view).create().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
        }
        val bindingDialog = DialogPwdBinding.bind(view)
        bindingDialog.ivClose.setOnClickListener {
            msgPwdDialog?.dismiss()

        }
        bindingDialog.btnOk.setOnClickListener {

            val password = bindingDialog.etInput.text.toString()
            if (password.isEmpty()) {
                toast(getString(R.string.my_wallet_password_tips))
                return@setOnClickListener
            }
            loading.show()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    wallet.let { w ->
                        sendMsgTrans(password, callbackId, data)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }


        }
    }

    private val delegate by inject<LoginDelegate>()

    private fun sendMsgTrans(password: String, callbackId: Int, data: String) {
        val mnem: String = delegate.preference.getMnemonicString(password) ?: ""
        chainId.let { cid ->
            val privKey = GoWallet.getPrikey(
                if (cid == 2999L) "ETH" else name ?: "", mnem
            )
            handleMessageSign(privKey, callbackId, data)
        }
    }

    private fun sendMsgTrans2(mnem: String, callbackId: Int, data: String) {
        chainId.let { cid ->
            val privKey = GoWallet.getPrikey(
                "ETH", mnem
            )
            handleMessageSign(privKey, callbackId, data)
        }
    }

    private fun handleMessageSign(prikey: String, callbackId: Int, data: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bData = getEthereumMessage(data)
                //报错闪退处理:app初始化需要   System.loadLibrary("TrustWalletCore")
                val digest = Hash.keccak256(bData)
                val pk = PrivateKey(Walletapi.hexTobyte(prikey))
                val signature = pk.sign(digest, Curve.SECP256K1)
                val signHex = Numeric.toHexString(signature)
                withContext(Dispatchers.Main) {
                    disMessage()
                    val callback: String = String.format(JS_CALLBACK_ON, callbackId, signHex)
                    //All WebView methods must be called on the same thread
                    //所以都放在主线程
                    binding.webDapp.evaluateJavascript(callback) { message: String? ->
                        Log.v("tag", "$message")
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun gotoSetFee() {
        // 优先使用 origGasPirce/origGas，未初始化则回退到 cGasPrice/cGas
        val baseGasPriceBigInt = when {
            ::origGasPirce.isInitialized -> origGasPirce
            ::cGasPrice.isInitialized -> cGasPrice
            else -> return
        }
        val baseGasBigInt = when {
            ::origGas.isInitialized -> origGas
            ::cGas.isInitialized -> cGas
            else -> return
        }
        // 确保 fee dialog 关闭后主弹框状态正常
        val dialog = BottomSheetDialog(this).apply {
            setOnDismissListener {
                // 恢复主弹框的等级文字显示
                setLevel(tvLevel)
                // 确保主弹框可再次点击
                payDialog?.window?.let { window ->
                    window.decorView.post {
                        window.decorView.isFocusableInTouchMode = true
                        window.decorView.requestFocus()
                    }
                }
            }
        }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fee_selection, null)
        dialog.setContentView(dialogView)

        val baseGasPrice = baseGasPriceBigInt.toDouble()
        val baseGas = baseGasBigInt.toDouble()
        val va9 = 10.0.pow(9.0)
        val va18 = 10.0.pow(18.0)

        // 计算各档位费用: gasPrice * gas / 10^18
        fun calcFee(gasPrice: Double, gas: Double): Double = (gasPrice * gas) / va18

        // 费用选项: 档位名称, gasPrice, gas, 预计时间
        data class FeeOption(val position: Int, val name: String, val gasPrice: Double, val gas: Double, val timeText: String)

        val feeOptions = listOf(
            FeeOption(0, getString(R.string.fee_fast), baseGasPrice * 1.5, baseGas, getString(R.string.fee_estimated_time, "15秒")),
            FeeOption(1, getString(R.string.fee_recommended), baseGasPrice, baseGas, getString(R.string.fee_estimated_time, "30秒")),
            FeeOption(2, getString(R.string.fee_slow), baseGasPrice * 0.8, baseGas, getString(R.string.fee_estimated_time, "60秒")),
            FeeOption(FEE_CUSTOM_POSITION, getString(R.string.fee_custom), baseGasPrice, baseGas, getString(R.string.fee_estimated_time, "--"))
        )

        // 关闭按钮
        dialogView.findViewById<ImageView>(R.id.iv_fee_selection_close)?.setOnClickListener {
            dialog.dismiss()
        }

        val optionsContainer = dialogView.findViewById<LinearLayout>(R.id.ll_fee_options_container) ?: return
        val customGasLayout = dialogView.findViewById<LinearLayout>(R.id.ll_custom_gas)

        val optionViews = mutableListOf<View>()
        val optionCheckIcons = mutableListOf<ImageView>()

        val chainNameStr = chainName ?: ""
        val gasPriceGwei = baseGasPrice / va9

        for (option in feeOptions) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_fee_option, optionsContainer, false)
            val checkIcon = itemView.findViewById<ImageView>(R.id.iv_fee_option_check)
            val nameTv = itemView.findViewById<TextView>(R.id.tv_fee_option_name)
            val timeTv = itemView.findViewById<TextView>(R.id.tv_fee_option_time)
            val estimatedTv = itemView.findViewById<TextView>(R.id.tv_fee_option_estimated)
            val estimatedCryptoTv = itemView.findViewById<TextView>(R.id.tv_fee_option_estimated_crypto)

            nameTv?.text = option.name
            timeTv?.text = option.timeText

            val feeValue = calcFee(option.gasPrice, option.gas)
            estimatedTv?.text = "$feeValue".toPlainStr(6)
            estimatedCryptoTv?.text = "${option.gasPrice / va9} Gwei x ${option.gas.toLong()} | $feeValue $chainNameStr"

            // 选中状态
            if (option.position == feePosition) {
                checkIcon?.setImageResource(R.drawable.ic_fee_option_checked)
                itemView.setBackgroundResource(R.drawable.bg_fee_option_selected)
            } else {
                checkIcon?.setImageResource(R.drawable.ic_fee_option_unchecked)
                itemView.setBackgroundResource(R.drawable.bg_fee_option)
            }

            itemView.setOnClickListener {
                optionViews.forEachIndexed { index, view ->
                    val icon = optionCheckIcons[index]
                    if (view == itemView) {
                        icon.setImageResource(R.drawable.ic_fee_option_checked)
                        view.setBackgroundResource(R.drawable.bg_fee_option_selected)
                    } else {
                        icon.setImageResource(R.drawable.ic_fee_option_unchecked)
                        view.setBackgroundResource(R.drawable.bg_fee_option)
                    }
                }

                customGasLayout?.visibility = if (option.position == FEE_CUSTOM_POSITION) View.VISIBLE else View.GONE

                if (option.position == FEE_CUSTOM_POSITION) {
                    customGasLayout?.post {
                        dialogView.findViewById<androidx.core.widget.NestedScrollView>(R.id.nsv_fee_selection)
                            ?.smoothScrollTo(0, customGasLayout?.bottom ?: 0)
                    }
                }

                // 非自定义模式直接更新
                if (option.position != FEE_CUSTOM_POSITION) {
                    val dGear = DGear(
                        gas = option.gas.toLong().toBigInteger(),
                        gasPrice = option.gasPrice.toLong().toBigInteger(),
                        position = option.position
                    )
                    LiveEventBus.get<DGear>(GoWallet.LIVE_KEY_FEE).post(dGear)
                    itemView.postDelayed({ dialog.dismiss() }, 200)
                }
            }

            optionViews.add(itemView)
            optionCheckIcons.add(checkIcon ?: ImageView(this))
            optionsContainer.addView(itemView)
        }

        // 自定义Gas初始可见性
        customGasLayout?.visibility = if (feePosition == FEE_CUSTOM_POSITION) View.VISIBLE else View.GONE

        // 自定义Gas确认按钮
        val etCustomGas = dialogView.findViewById<EditText>(R.id.et_custom_gas)
        val etCustomGasLimit = dialogView.findViewById<EditText>(R.id.et_custom_gas_limit)

        // 预填当前值
        if (feePosition == FEE_CUSTOM_POSITION && ::cGasPrice.isInitialized && ::cGas.isInitialized) {
            etCustomGas?.setText((cGasPrice.toDouble() / va9).toString().toPlainStr(2))
            etCustomGasLimit?.setText(cGas.toString())
        }

        dialogView.findViewById<View>(R.id.ll_custom_gas)?.let { customLayout ->
            val confirmBtn = TextView(this).apply {
                text = getString(R.string.ok)
                setTextColor(ContextCompat.getColor(this@DappActivity, android.R.color.white))
                textSize = 14f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_fee_recommended_badge)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp2px(12)
                }
                setPadding(0, dp2px(12), 0, dp2px(12))
                setOnClickListener {
                    val gasPriceStr = etCustomGas?.text?.toString()
                    val gasLimitStr = etCustomGasLimit?.text?.toString()
                    val gasPrice = gasPriceStr?.toDoubleOrNull()
                    val gasLimit = gasLimitStr?.toLongOrNull()
                    if (gasPrice != null && gasLimit != null && gasLimit > 0) {
                        val dGear = DGear(
                            gas = gasLimit.toBigInteger(),
                            gasPrice = (gasPrice * va9).toLong().toBigInteger(),
                            position = FEE_CUSTOM_POSITION
                        )
                        LiveEventBus.get<DGear>(GoWallet.LIVE_KEY_FEE).post(dGear)
                    }
                    dialog.dismiss()
                }
            }
            (customLayout as? LinearLayout)?.addView(confirmBtn)
        }

        dialog.show()
    }

    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }


    private fun showGasUI(gasPrice: Long, gas: Long, chainName: String?) {
        try {
            cGasPrice = gasPrice.toBigInteger()
            val va9 = 10.0.pow(9.0)
            val va18 = 10.0.pow(18.0)
            val newGasPirce = "${gasPrice / va9}".toPlainStr(2)

            dGas = (gasPrice * gas) / va18
            val newGas = "$dGas".toPlainStr(6)
            tvFee?.text = "$newGas $chainName"
            tvWCFee?.text = "$newGas $chainName = Gas($gas)*GasPrice($newGasPirce GWEI)"
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun customChain(): Boolean {
        /*chainName?.let {
            if (it == "ETH" || it == "BNB") {
                return true
            }
        }*/
        return true
    }


    private var pwdDialog: Dialog? = null
    private fun showPWD(wallet: PWallet, createTran: CreateTran) {
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_pwd, null)
            pwdDialog = AlertDialog.Builder(this).setView(view).create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
            val bindingDialog = DialogPwdBinding.bind(view)
            bindingDialog.ivClose.setOnClickListener {
                pwdDialog?.dismiss()
            }
            bindingDialog.btnOk.setOnClickListener {
                try {
                    val password = bindingDialog.etInput.text.toString()
                    if (password.isEmpty()) {
                        toast(getString(R.string.my_wallet_password_tips))
                        return@setOnClickListener
                    }
                    loading.show()
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            sendTrans(password, createTran)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }


                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private suspend fun sendTrans(password: String, createTran: CreateTran) {

        val mnem: String = delegate.preference.getMnemonicString(password) ?: ""
        chainName?.let { name ->
            val privKey = GoWallet.getPrikey(
                if (name == "BTY") "ETH" else name, mnem
            )
            signAndSend(name, privKey, createTran)
        }

    }

    private suspend fun sendTrans2(mnem: String, createTran: CreateTran) {
        chainName?.let { name ->
            val privKey = GoWallet.getPrikey("ETH", mnem)
            signAndSend(name, privKey, createTran)
        }

    }

    private suspend fun signAndSend(name: String, privKey: String, createTran: CreateTran) {
        try {
            //发送后重置为经济
            feePosition = 2
            val createJson = gson.toJson(createTran)
            val bCreate = Walletapi.stringTobyte(createJson)

            val signed = GoWallet.signTran(
                if (name == "BTY") "BTYETH" else name, bCreate, privKey, 2
            )
            signed?.let {
                if (name == "BTY") {
                    val send = walletRepository.sendRawTransaction(signed)
                    withContext(Dispatchers.Main) {
                        if (send.isSucceed()) {
                            send.data()?.let { sendHash ->
                                sendSuccess(sendHash, createTran.leafPosition)
                            }
                        } else {
                            //给H5一个反馈来刷新按钮
                            //弹出发送失败，数据解析失败，一般是发送到主网超时，或者主网卡住了
                            sendFail(send.error(), createTran.leafPosition);
                        }
                    }
                } else {
                    val send = GoWallet.sendTran(name, signed, "")
                    val sendHash = JSONObject(send!!).getString("result")
                    withContext(Dispatchers.Main) {
                        sendSuccess(sendHash, createTran.leafPosition)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private val gson = GsonBuilder().serializeNulls().create()

    private fun sendSuccess(sendHash: String, leafPosition: Long) {
        dis()
        toast(getString(R.string.send_suc_str))
        val callback = String.format(JS_CALLBACK_ON, leafPosition, sendHash)
        binding.webDapp.evaluateJavascript(callback) { value: String? ->
        }
    }

    private fun sendFail(error: String, leafPosition: Long) {
        dis()
        toast("${getString(R.string.basic_error_send)}")
        val callback: String = String.format(
            JS_CALLBACK_ON_FAILURE, leafPosition, JS_FAIL
        )
        binding.webDapp.evaluateJavascript(callback) { value: String? ->
        }
    }

    private fun dis() {
        loading.dismiss()
        pwdDialog?.dismiss()
        payDialog?.dismiss()
    }

    private fun disMessage() {
        loading.dismiss()
        msgPwdDialog?.dismiss()
        messageDialog?.dismiss()
    }


    //--------------------------test--------------------------
    private val MESSAGE_PREFIX: String = "\u0019Ethereum Signed Message:\n"
    private fun getEthereumMessagePrefix(messageLength: Int): ByteArray {
        val pre = "$MESSAGE_PREFIX${messageLength}"
        return pre.toByteArray()
    }

    private fun getEthereumMessage(message: String): ByteArray {
        val encodedMessage: ByteArray = message.toByteArray()
        val prefix: ByteArray = getEthereumMessagePrefix(encodedMessage.size)
        val result = ByteArray(prefix.size + encodedMessage.size)
        System.arraycopy(prefix, 0, result, 0, prefix.size)
        System.arraycopy(encodedMessage, 0, result, prefix.size, encodedMessage.size)
        return result
    }


    private fun showBottomDialog() {
        val bottomDialog = AlertDialog.Builder(this).create()
        val bottomBinding = DialogDappBottomBinding.inflate(layoutInflater)
        bottomBinding.tvRefresh.setOnClickListener {
            binding.webDapp.reload()
            bottomDialog.dismiss()
        }
        bottomBinding.tvBrowserOpen.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.data = Uri.parse(url)
            startActivity(intent)
            bottomDialog.dismiss()
        }
        bottomBinding.tvCopyUrl.setOnClickListener {
            ClipboardUtils.clip(this, url)
            bottomDialog.dismiss()
        }
        bottomBinding.tvExit.setOnClickListener {
            bottomDialog.dismiss()
            finish()
        }
        configWindow(bottomDialog, Gravity.BOTTOM)
        bottomDialog.setView(bottomBinding.root)
        bottomDialog.show()
    }


    private val LIKE_URL_KEY = "like_url_key"
    private val mUrlList: MutableList<LikeDappItem> = ArrayList()

    data class LikeDappItem(
        val name: String,
        val url: String,
        val icon: String
    )
    fun configWindow(alertDialog: AlertDialog, gravity: Int) {
        val window = alertDialog.window
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.decorView?.setPadding(0, 0, 0, 0)
        window?.setGravity(gravity)
        val lp = window?.attributes
        lp?.width = WindowManager.LayoutParams.MATCH_PARENT
        lp?.height = WindowManager.LayoutParams.WRAP_CONTENT
        window?.attributes = lp
    }


}