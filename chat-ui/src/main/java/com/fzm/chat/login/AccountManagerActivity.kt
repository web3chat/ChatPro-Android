package com.fzm.chat.login

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.adapter.AccountManagerAdapter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.ui.PasswordVerifyFragment
import com.fzm.chat.core.MyWallet
import com.fzm.chat.core.service.MessageService
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.session.data.UserPreference
import com.fzm.chat.core.utils.CipherUtils
import com.fzm.chat.databinding.ActivityAccountManagerBinding
import com.fzm.chat.login.words.ImportAccountViewModel
import com.fzm.chat.router.app.AppModule
import com.fzm.chat.router.biz.PasswordVerifier
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.router.route
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.chat.router.wallet.WalletService
import com.fzm.chat.utils.AccountProfileCache
import com.fzm.wallet.sdk.BWallet
import com.fzm.wallet.sdk.MnemonicManager
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.wallet.sdk.utils.GoWallet
import com.zjy.architecture.ext.bytes2Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.anko.toast
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.litepal.LitePal
import org.litepal.extension.find
import org.litepal.extension.findAll
import walletapi.Walletapi

/**
 * @author zhengjy
 * @since 2021/01/12
 * Description: 账户管理页面，展示本地所有已导入的钱包账户列表，
 * 点击某个账户后验证密码并切换登录身份
 */
@Route(path = MainModule.ACCOUNT_MANAGER)
class AccountManagerActivity : BizActivity() {

    private val binding by init { ActivityAccountManagerBinding.inflate(layoutInflater) }
    private val loginDelegate by inject<LoginDelegate>()
    private val walletService by route<WalletService>(WalletModule.SERVICE)
    override val root: View
        get() = binding.root

    /**
     * 初始化账户列表视图：
     * 1. 从LitePal加载所有PWallet钱包记录
     * 2. 为每个钱包查询Coin.address（链上地址）和聊天地址（PWallet.user）
     * 3. 用聊天地址从 MMKV 缓存中同步读取每个账户的头像和昵称，无需等待异步回调
     * 4. 点击item时弹出密码验证弹窗，验证通过后解密助记词、派生密钥对、切换登录账户
     */
    override fun initView() {
        val pWallets = LitePal.findAll<PWallet>()

        // 钱包ID -> 第一个启用Coin的链上地址
        val addressMap = mutableMapOf<Long, String>()
        // 钱包ID -> 聊天地址（PWallet.user 在 importMnem 时存储的 chat address）
        val chatAddrMap = mutableMapOf<Long, String>()

        for (wallet in pWallets) {
            val coin = LitePal.where(
                "pwallet_id = ? and status = ?",
                wallet.id.toString(),
                Coin.STATUS_ENABLE.toString()
            ).find(Coin::class.java, true).firstOrNull()
            addressMap[wallet.id] = coin?.address.orEmpty()
            chatAddrMap[wallet.id] = wallet.user.orEmpty()
        }

        // 收集所有不重复的聊天地址，从 MMKV 读取缓存的头像和昵称
        val profileMap = chatAddrMap.values.filter { it.isNotBlank() }.distinct().associateWith { addr ->
            AccountProfileCache.getAvatar(addr) to AccountProfileCache.getNickname(addr)
        }

        val currentWalletId = try { MyWallet.getID() } catch (e: Exception) { -1L }
        val adapter = AccountManagerAdapter(this, profileMap, addressMap, chatAddrMap, currentWalletId)
        adapter.setData(pWallets)
        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        adapter.setOnItemClickListener { p ->
            val pWallet = pWallets[p]
            if (pWallet.id == currentWalletId && loginDelegate.isLogin()) {
                toast("当前已登录该账号，无需切换")
                return@setOnItemClickListener
            }
            val dialog by route<PasswordVerifyFragment>(
                MainModule.VERIFY_ENC_PWD,
                bundleOf("type" to 0, "waletId" to pWallet.id)
            )
            dialog?.setOnPasswordVerifyListener(object :
                PasswordVerifier.OnPasswordVerifyListener {
                override fun onSuccess(password: String) {
                    loading(true)
                    lifecycleScope.launch {
                        try {
                            loginDelegate.logoutSuspend()
                            val (publicKey, privateKey, mnem) = withContext(Dispatchers.IO) {
                                val pass = GoWallet.encPasswd(password)!!
                                val decMnem = GoWallet.decMenm(pass, pWallet.mnem)
                                val hdWallet = CipherUtils.getHDWallet(Walletapi.TypeBtyString, decMnem)
                                Triple(
                                    hdWallet!!.newKeyPub(0).bytes2Hex(),
                                    hdWallet.newKeyPriv(0).bytes2Hex(),
                                    decMnem
                                )
                            }
                            loginDelegate.loginSuspendCache(publicKey, privateKey, mnem, password)
                            MyWallet.setId(pWallet.id)
                            val address = CipherUtils.pubToAddress(publicKey)
                            try {
                                walletService?.importMnem(address, mnem, address, password, "", "")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            startService(Intent(this@AccountManagerActivity, MessageService::class.java))
                            ARouter.getInstance().build(AppModule.MAIN)
                                .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                                .navigation()
                            finish()
                        } catch (e: Exception) {
                            toast(e.message ?: "切换账号失败")
                        } finally {
                            dismiss()
                        }
                    }
                }
            })
            dialog?.show(supportFragmentManager, "VERIFY_ENC_PWD")
        }
    }

    override fun initData() {
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
    }

}
