package com.fzm.chat.login.words

import android.graphics.Color
import android.view.View
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.databinding.ActivityCreateWalletBinding
import com.fzm.chat.router.main.MainModule
import com.zjy.architecture.util.other.BarUtils
import org.jetbrains.anko.toast


@Route(path = MainModule.CREATE_WALLET)
class CreateWalletActivity : BizActivity() {

    private val binding by init { ActivityCreateWalletBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    override fun setSystemBar() {
        BarUtils.setStatusBarColor(this, Color.parseColor("#333649"), 0)
        BarUtils.setStatusBarLightMode(this, false)
        window?.navigationBarColor = Color.parseColor("#2B292F")
    }

    override fun initView() {
        binding.ctbTitle.setOnLeftClickListener { onBackPressedSupport() }
    }

    override fun initData() {

    }

    override fun setEvent() {
        binding.btnOk.setOnClickListener {
            val password = binding.etPassword.text.toString()
            val passwordAgain = binding.etPasswordAgain.text.toString()
            if (password.isEmpty() || passwordAgain.isEmpty()) {
                toast("请输入密码")
                return@setOnClickListener
            }
         if (password.length !in 8..16 || passwordAgain.length !in 8..16) {
                toast("请输入8-16位密码")
                return@setOnClickListener
            }

            if(password != passwordAgain){
                toast("两次密码输入不相同！")
                return@setOnClickListener
            }
            ARouter.getInstance().build(MainModule.CREATE_MNEM)
                .withString("password", password)
                .navigation()
        }
    }


}