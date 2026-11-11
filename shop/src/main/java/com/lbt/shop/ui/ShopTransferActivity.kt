package com.lbt.shop.ui

import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.widget.doOnTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.biz.base.EmptyTabFragment
import com.fzm.chat.router.biz.ResultReceiver
import com.fzm.chat.router.route
import com.fzm.chat.router.shop.ShopModule
import com.fzm.chat.router.shop.ShopService
import com.fzm.chat.router.wallet.WalletModule
import com.fzm.shop.R
import com.fzm.shop.databinding.ActivityShopTransferBinding
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.widget.dialog.EasyDialog
import com.zjy.architecture.base.instance
import com.zjy.architecture.ext.toast
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2021/10/18
 * Description:
 */
@Route(path = ShopModule.TRANSFER_SHOP)
class ShopTransferActivity : BizActivity() {

    @JvmField
    @Autowired
    var showNFT: Boolean = false

    @JvmField
    @Autowired
    var token: Coin? = null

    private var currentToken: Coin? = null
    private var shopAddress = ""

    private var amount: Double = 0.0

    private val viewModel by viewModel<ShopViewModel>()
    private val shopService by route<ShopService>(ShopModule.SERVICE)

    private val binding by init<ActivityShopTransferBinding>()

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
        if (showNFT) {
            binding.shopDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            val token by route<Fragment>(
                WalletModule.DRAWER_ASSETS,
                bundleOf(
                    "showNFT" to showNFT,
                    "chain" to "BTY"
                )
            ) {
                EmptyTabFragment.create()
            }
            (token as? ResultReceiver<Coin>?)?.setOnResultReceiver {
                changeToken(it)
                binding.shopDrawer.closeDrawer(GravityCompat.END)
            }
            supportFragmentManager.commit { add(R.id.fcv_container, token!!) }
        } else {
            binding.shopDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            val token by route<Fragment>(
                WalletModule.DRAWER_ASSETS,
                bundleOf(
                    "showNFT" to showNFT,
                    "chain" to "BTY"
                )
            ) {
                EmptyTabFragment.create()
            }
            (token as? ResultReceiver<Coin>?)?.setOnResultReceiver {
                changeToken(it)
                binding.shopDrawer.closeDrawer(GravityCompat.END)
            }
            supportFragmentManager.commit { add(R.id.fcv_container, token!!) }
        }
        if (token != null) {
            changeToken(token)
        }
    }

    override fun initData() {
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.shopAddress.observe(this) {
            shopAddress = it
            checkTransferState()
            if (shopAddress.isEmpty()) {
                bindShopAddress()
            }
        }
        viewModel.transferResult.observe(this) {
            dismiss()
            toast(getString(R.string.shop_transfer_complete))
            finish()
        }
    }

    private fun bindShopAddress() {
        EasyDialog.Builder()
            .setHeaderTitle(getString(R.string.biz_tips))
            .setContent(getString(R.string.shop_bind_first))
            .setCancelable(false)
            .setBottomLeftText(getString(R.string.biz_cancel))
            .setBottomLeftClickListener { dialog ->
                dialog.dismiss()
                finish()
            }
            .setBottomRightText(getString(R.string.shop_go_bind))
            .setBottomRightClickListener { dialog ->
                dialog.dismiss()
                shopService?.openShopHomePage(instance)
            }
            .create(instance)
            .show()
    }

    private fun changeToken(token: Coin?) {
        if (token == null) return
        currentToken = token
        binding.etAmount.setText("")
        token.apply {
            binding.tvSelectedToken.text = name
            binding.tvTokenName.text = name
            binding.tvBalance.text = getString(R.string.shop_transferable_format, balance, name)
        }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.etAmount.doOnTextChanged { text, _, _, _ ->
            amount = text?.toString()?.toDouble() ?: 0.0
            checkTransferState()
        }
        binding.tvSelectedToken.setOnClickListener {
            binding.shopDrawer.openDrawer(GravityCompat.END)
        }
        binding.ivTransfer.setOnClickListener {
            currentToken?.also { s ->
                finish()
                shopService?.openShopTransferPage(this, s.name)
            }
        }
        binding.tvTotal.setOnClickListener {
            binding.etAmount.setText(currentToken?.balance ?: "")
        }
        binding.tvSubmit.setOnClickListener {
            if (shopAddress.isEmpty()) {
                bindShopAddress()
                return@setOnClickListener
            }
            currentToken?.also { token ->
                viewModel.transfer(shopAddress, token.name, amount)
            }
        }
    }

    private fun checkTransferState() {
        binding.tvSubmit.isEnabled =
            amount > 0.0 && currentToken != null && shopAddress.isNotEmpty()
    }

    override fun onResume() {
        super.onResume()
        viewModel.getShopAddress()
    }

    override fun onBackPressedSupport() {
        if (binding.shopDrawer.isDrawerOpen(GravityCompat.END)) {
            binding.shopDrawer.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressedSupport()
        }
    }
}