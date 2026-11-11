package com.fzm.chat.search

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.fzm.chat.MMkvUtil
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizFragment
import com.fzm.chat.biz.customerservice.AiCustomerService
import com.fzm.chat.databinding.ActivitySearchDappBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.ui.QRScanActivity
import com.fzm.chat.utils.PreferencesUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.king.zxing.Intents
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.other.BarUtils

/**
 * @author zhengjy
 * @since 2020/12/14
 * Description:
 */
@Route(path = MainModule.FIND)
class SearchDappFragment : BizFragment() {

    private val mUrlList: MutableList<String> = ArrayList()

    private val binding by init<ActivitySearchDappBinding>()

    override val root: View
        get() = binding.root
    private val HISTORY_URL_KEY = "history_url_key"

    private val REQUEST_CODE = 2

    override fun setEvent() {
        binding.ivScan.setOnClickListener {
            // 用 Fragment 自身的 startActivityForResult，确保扫码结果能回传到本 Fragment
            val intent = Intent(requireContext(), QRScanActivity::class.java)
                .putExtra("forceReturn", true)
            startActivityForResult(intent, REQUEST_CODE)
        }
        // AI 客服快捷入口：与主页悬浮按钮走同一个入口方法（DApp 浏览器，注入 window.ethereum）
        binding.llAiCsEntry.setOnClickListener {
            AiCustomerService.open(requireContext())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_CODE && resultCode == AppCompatActivity.RESULT_OK) {
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
            PreferencesUtils.putString(requireContext(), HISTORY_URL_KEY, urls)
            mAdapter.notifyDataSetChanged()
            updateEmpty()
        }

        try {
            if (MMkvUtil.decodeBoolean(url)) {
                gotoDapp(url)
            } else {
                MaterialDialog.Builder(requireContext())
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

    /** 历史条目彩色圆形图标配色（柔和高识别度色板） */
    private val initialColors = intArrayOf(
        0xFF32B2F7.toInt(),
        0xFF2BC8BA.toInt(),
        0xFF7B6CF6.toInt(),
        0xFFFF9F43.toInt(),
        0xFFFF6B81.toInt(),
        0xFF3ECF7A.toInt(),
        0xFFE96BA8.toInt(),
        0xFF5B7CFF.toInt()
    )

    override fun initView(view: View, savedInstanceState: Bundle?) {
        // 发现页作为主界面 Tab 内页面，不显示标题栏与返回键
        binding.ctbTitle.visibility = View.GONE
        // 主界面为沉浸式全屏，需手动为内容顶部预留状态栏（刘海）高度
        BarUtils.addPaddingTopEqualStatusBarHeight(requireContext(), binding.root)
        binding.tvSearch.setOnClickListener {
            openUrl(binding.etSearch.text.toString())
        }


        val linearLayoutManager = LinearLayoutManager(requireContext())

        mAdapter =
            object : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_dapp_history, mUrlList) {
                override fun convert(holder: BaseViewHolder, item: String) {
                    val host = runCatching { Uri.parse(item).host }.getOrNull().orEmpty()
                    val title = host.ifBlank { item }.removePrefix("www.")
                    holder.setText(R.id.tv_title, title)
                    holder.setText(R.id.tv_url, item)
                    val tvInitial = holder.getView<TextView>(R.id.tv_initial)
                    tvInitial.text = title.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                    val color = initialColors[Math.floorMod(item.hashCode(), initialColors.size)]
                    tvInitial.backgroundTintList = ColorStateList.valueOf(color)
                }

            }
        mAdapter.setOnItemClickListener { adapter, view, position ->
            val url = mUrlList[position]
            gotoDapp(url)
        }
        mAdapter.setOnItemLongClickListener { adapter, view, position ->
            MaterialDialog.Builder(requireContext())
                .onPositive { dialog, which ->
                    mUrlList.removeAt(position)
                    mAdapter.notifyDataSetChanged()
                    updateEmpty()
                    val urls = Gson().toJson(mUrlList)
                    PreferencesUtils.putString(requireContext(), HISTORY_URL_KEY, urls)
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
        updateEmpty()
    }

    private fun updateEmpty() {
        val empty = mUrlList.isEmpty()
        binding.llEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun initData() {
        val testurl = PreferencesUtils.getString(requireContext(), HISTORY_URL_KEY)
        if (!TextUtils.isEmpty(testurl)) {
            val stringList =
                Gson().fromJson<List<String>>(testurl, object : TypeToken<List<String?>?>() {}.type)
            mUrlList.clear()
            mUrlList.addAll(stringList)
            mAdapter.notifyDataSetChanged()
        }
        updateEmpty()
    }


}