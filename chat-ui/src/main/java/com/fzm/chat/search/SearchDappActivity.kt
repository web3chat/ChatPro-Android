package com.fzm.chat.search

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.View.OnClickListener
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.MMkvUtil
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.customerservice.AiCustomerService
import com.fzm.chat.core.data.bean.SearchResult
import com.fzm.chat.databinding.ActivitySearchDappBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.utils.PreferencesUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.king.zxing.Intents
import com.zjy.architecture.ext.toast

@Route(path = MainModule.SEARCH_DAPP)
class SearchDappActivity : BizActivity() {
    private val mUrlList: MutableList<String> = ArrayList()
    private val binding by lazy { ActivitySearchDappBinding.inflate(layoutInflater) }
    override val root: View
        get() = binding.root
    private val HISTORY_URL_KEY = "history_url_key"

    private val REQUEST_CODE = 2
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.ctbTitle.setOnLeftClickListener { onBackPressedSupport() }
        binding.tvSearch.setOnClickListener {
            openUrl(binding.etSearch.text.toString())
        }
        // AI 客服快捷入口：与主页悬浮按钮同一个入口方法（DApp 浏览器，注入 window.ethereum）
        binding.llAiCsEntry.setOnClickListener {
            AiCustomerService.open(this)
        }
    }

    override fun setEvent() {
        binding.ivScan.setOnClickListener {
            ARouter.getInstance().build(MainModule.QR_SCAN).withBoolean("forceReturn",true).navigation(this,REQUEST_CODE)

        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if(requestCode == REQUEST_CODE && resultCode == RESULT_OK){
            val result = intent?.getStringExtra(Intents.Scan.RESULT)
            binding.etSearch.setText(result)
            // 扫到的是网址则直接打开，否则只填入输入框
            if (!result.isNullOrEmpty() &&
                (result.startsWith("http://", ignoreCase = true) || result.startsWith("https://", ignoreCase = true))
            ) {
                openUrl(result)
            }
        }

    }

    /** 打开网址：校验、记录历史、弹免责提示后跳转 DApp */
    private fun openUrl(url: String) {
        if (url.isEmpty()) {
            toast(getString(R.string.input_www))
            return
        }
        if (!mUrlList.contains(url)) {
            mUrlList.add(url)
            val urls = Gson().toJson(mUrlList)
            PreferencesUtils.putString(this, HISTORY_URL_KEY, urls)
            mAdapter.notifyDataSetChanged()
        }

        try {
            if (MMkvUtil.decodeBoolean(url)) {
                gotoDapp(url)
            } else {
                MaterialDialog.Builder(this@SearchDappActivity)
                    .negativeText(getString(R.string.cancel))
                    .positiveText(getString(R.string.ok))
                    .title(getString(R.string.explore_title))
                    .content(getString(R.string.explore_disclaimer))
                    .checkBoxPrompt(
                        getString(R.string.no_dotip),
                        false
                    ) { buttonView, isChecked ->
                        MMkvUtil.encode(url, isChecked)
                    }
                    .onNegative { dialog, which ->
                    }
                    .onPositive { dialog, which ->
                        gotoDapp(url)
                    }.build().show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun gotoDapp(url: String) {
        /* val count = LitePal.count<PWallet>()
         if (count == 0) {
             toast(getString(R.string.create_wallet_pre))
             return
         }
         val id = MyWallet.getId()
         val wallet = LitePal.find<PWallet>(id)
         if (wallet?.type == PWallet.TYPE_ADDR_KEY) {
             toast(getString(R.string.str_addr_no))
             return
         }
         val newUrl = GoWallet.getNewUrl(url)
         ARouter.getInstance().build(RouterPath.APP_DAPP)
             .withString(RouterPath.PARAM_URL, newUrl).navigation()*/

        val newUrl = getNewUrl(url)
        ARouter.getInstance().build(MainModule.APP_DAPP)
            .withString(MainModule.PARAM_URL, newUrl).navigation()

    }
    private val HTTP_HEADER = "http://"
    private val HTTPS_HEADER = "https://"
    private fun getNewUrl(url: String): String {
        val lowUrl = url.lowercase()
        return if (!lowUrl.startsWith(HTTP_HEADER) && !lowUrl.startsWith(HTTPS_HEADER)) {
            "$HTTPS_HEADER$lowUrl"
        } else {
            lowUrl
        }
    }

    private lateinit var mAdapter: BaseQuickAdapter<String, BaseViewHolder>
    override fun initView() {
        val linearLayoutManager = LinearLayoutManager(this)

        mAdapter = object : BaseQuickAdapter<String, BaseViewHolder>(R.layout.layout_text_m,mUrlList) {
            override fun convert(holder: BaseViewHolder, item: String) {
                holder.setText(R.id.tv_value, item)
            }

        }
        mAdapter.setOnItemClickListener { adapter, view, position ->
            val url = mUrlList[position]
            gotoDapp(url)
        }
        mAdapter.setOnItemLongClickListener { adapter, view, position ->
            MaterialDialog.Builder(this)
                .onPositive { dialog, which ->
                    mUrlList.removeAt(position)
                    mAdapter.notifyDataSetChanged()
                    val urls = Gson().toJson(mUrlList)
                    PreferencesUtils.putString(this, HISTORY_URL_KEY, urls)
                }
                .title(getString(R.string.del_str))
                .content(getString(R.string.del_tip_str))
                .positiveText(getString(R.string.ok))
                .negativeText(getString(R.string.cancel))
                .show();

            false
        }
        binding.rvList.layoutManager = linearLayoutManager
        binding.rvList.adapter = mAdapter

    }


    override fun initData() {
        val testurl = PreferencesUtils.getString(this, HISTORY_URL_KEY)
        if (!TextUtils.isEmpty(testurl)) {
            val stringList =
                Gson().fromJson<List<String>>(testurl, object : TypeToken<List<String?>?>() {}.type)
            mUrlList.clear()
            mUrlList.addAll(stringList)
            mAdapter.notifyDataSetChanged()
        }
    }
}