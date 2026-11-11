package com.fzm.chat.login.words

import android.content.Intent
import android.graphics.Color
import android.view.View
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.BuildConfig
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.databinding.ActivityImportAccountBinding
import com.fzm.chat.router.app.AppModule
import com.fzm.chat.router.main.MainModule
import com.king.zxing.Intents
import com.zjy.architecture.ext.checkSingle
import com.zjy.architecture.ext.toast
import com.zjy.architecture.util.other.BarUtils
import org.jetbrains.anko.toast
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.web3j.crypto.MnemonicUtils

/**
 * @author zhengjy
 * @since 2020/12/11
 * Description:
 */
@Route(path = MainModule.IMPORT_ACCOUNT)
class ImportAccountActivity : BizActivity() {

    private val binding by init { ActivityImportAccountBinding.inflate(layoutInflater) }

    private val viewModel by viewModel<ImportAccountViewModel>()

    private var block = false
    private val REQUEST_CODE = 1

    private lateinit var adapter: WordsAdapter
    private val wordsList = mutableListOf<String>()

    override val root: View
        get() = binding.root

    override fun setSystemBar() {
        BarUtils.setStatusBarColor(this, Color.WHITE, 0)
        BarUtils.setStatusBarLightMode(this, true)
        window?.navigationBarColor = Color.WHITE
    }

    override fun initView() {
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.importResult.observe(this) {
            if (it != null) {
                toast(R.string.chat_login_mnem_import_success)
                ARouter.getInstance().build(AppModule.MAIN).navigation()
                finish()
            } else {
                toast(R.string.chat_login_mnem_import_fail)
            }
        }

        binding.etMnem.doOnTextChanged { text, start, before, count ->
            text?.let {

                val cursorPosition = binding.etMnem.selectionStart
                var startIndex = it.lastIndexOf(' ', cursorPosition - 1)
                val curValue = it.substring(if (startIndex == -1) 0 else startIndex + 1, it.length)

                if (curValue.isNotEmpty()) {
                    val startsList = startsWithWords(curValue)
                    wordsList.clear()
                    wordsList.addAll(startsList)
                    adapter.notifyDataSetChanged()
                }

            }

        }
    }



    //助记词开头的模糊匹配：
    fun startsWithWords(prefix: String): List<String> {
        val words = MnemonicUtils.getWords()
        return words.filter { it.startsWith(prefix) }
    }

    override fun initData() {
            if(BuildConfig.DEBUG){
                binding.etWalletPassword.setText("123456abc")
                binding.etWalletPasswordAgain.setText("123456abc")
            }


        binding.rvStartsWords.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        adapter = WordsAdapter(this)
        adapter.setData(wordsList)
        adapter.setOnItemClickListener { position ->
            val word = wordsList[position]
            val cuMnem = binding.etMnem.text
            cuMnem?.let {
                val cursorPosition = binding.etMnem.selectionStart
                val startIndex = it.toString().lastIndexOf(' ', cursorPosition - 1)
                //最后一个单词 即第12个不添加空格，
                val repValue = if (countWords(it.toString()) == 12) "$word" else "$word "
                it.replace(if (startIndex == -1) 0 else startIndex + 1, cursorPosition, repValue)

                //每次点击都判断是否存在连续多个空格的情况，如果存在把多个连续空格变为1个空格
                //也可以不判断直接it.replace(Regex(" +"), " ")
                //这样已经很大程度上规避了多个空格防止生成不一样的地址的情况，但是还有用户主动输入空格的情况，所以点击导入的时候还要再次去掉空格以及结尾的空格
                if (hasMulSpaces(it.toString())) {
                    val newValue = it.replace(Regex(" +"), " ")
                    it.replace(0, it.length, newValue)
                }

            }

        }
        binding.rvStartsWords.adapter = adapter
    }

    private fun hasMulSpaces(input: String): Boolean {
        // 正则表达式匹配两个或更多连续的空格
        val pattern = Regex("  +")
        return pattern.containsMatchIn(input)
    }

    private fun countWords(text: String): Int {
        if (text.isBlank()) return 0

        // 使用正则表达式分割字符串，处理多个连续空格的情况
        val words = text.trim().split("\\s+".toRegex())

        // 过滤掉空字符串并返回数量
        return words.count { it.isNotBlank() }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnRightClickListener {
            ARouter.getInstance().build(MainModule.QR_SCAN).withBoolean("forceReturn", true)
                .navigation(this, REQUEST_CODE)
        }
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.etMnem.doOnTextChanged { text, start, count, after ->
            if (block) return@doOnTextChanged
            if (!text.isNullOrEmpty()) {
                val noSpace = text.replace(" ".toRegex(), "")
                val first = text.substring(0, 1)
                if (first.matches(ChatConst.REGEX_CHINESE.toRegex())) {
                    // 如果是中文助记词，则需要添加空格
                    val sb = StringBuilder()
                    for (i in noSpace.indices) {
                        sb.append(noSpace[i])
                        if ((i + 1) % 3 == 0 && i != noSpace.length - 1) {
                            sb.append(" ")
                        }
                    }
                    block = true
                    binding.etMnem.setText(sb)
                    binding.etMnem.setSelection(sb.length)
                    block = false
                }
            }
        }
        binding.btnImport.setOnClickListener {
            val mnem = binding.etMnem.text.toString()
            val walletPassword = binding.etWalletPassword.text.toString()
            val walletPasswordAgain = binding.etWalletPasswordAgain.text.toString()
            if (mnem.isEmpty()) {
                toast("请输入助记词")
                return@setOnClickListener
            }
            if (walletPassword.isEmpty() || walletPasswordAgain.isEmpty()) {
                toast("请输入密码")
                return@setOnClickListener
            }
            if (walletPassword.length !in 8..16 || walletPasswordAgain.length !in 8..16) {
                toast("请输入8-16位密码")
                return@setOnClickListener
            }
            if(walletPassword != walletPasswordAgain){
                toast("两次密码输入不相同！")
                return@setOnClickListener
            }

            viewModel.importMnemonic(binding.etMnem.text.toString(), walletPassword)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            val result = intent?.getStringExtra(Intents.Scan.RESULT)
            binding.etMnem.setText(result)
        }

    }
}