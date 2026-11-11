package com.fzm.chat.core.logic

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.fzm.chat.core.data.AppPreference
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.utils.toHttpUrl
import com.zjy.architecture.di.rootScope
import me.jessyan.retrofiturlmanager.InvalidUrlException
import me.jessyan.retrofiturlmanager.RetrofitUrlManager

/**
 * @author zhengjy
 * @since 2020/12/15
 * Description:服务器地址切换管理
 */
object ServerManager {

    val defaultChatUrl: String
        get() = localChatUrl

    private val manager: RetrofitUrlManager = rootScope.get()

    private var contractUrl: String = ""
    private var localChatUrl: String = ""
    private var walletUrl: String = ""
    private var oaUrl: String = ""
    private var shopUrl: String = ""

    /** 当前选中的聊天服务器变化通知（用于切换后立即重连，值为时间戳保证每次都能触发） */
    private val _chatServerChanged = MutableLiveData<Long>()
    val chatServerChanged: LiveData<Long>
        get() = _chatServerChanged

    /**
     * 应用上一次登录时的服务器配置
     *
     * @return 是否应用成功
     */
    fun loadLastServer(): Boolean {
        val contract = AppPreference.CONTRACT_URL
        val chat = AppPreference.CHAT_URL
        val oa = AppPreference.OA_URL
        val shop = AppPreference.SHOP_URL
        if (oa.isNotEmpty()) {
            changeOAServer(oa)
        }
        if (shop.isNotEmpty()) {
            changeShopServer(shop)
        }
        if (contract.isNotEmpty() && chat.isNotEmpty()) {
            changeContractServer(contract)
            changeLocalChatServer(chat)
            return true
        }
        return false
    }

    /**
     * 切换合约服务器地址
     */
    fun changeContractServer(url: String) {
        try {
            if (AppPreference.CONTRACT_URL != url) {
                AppPreference.CONTRACT_URL = url
            }
            contractUrl = url.toHttpUrl()
            manager.putDomain(ChatConst.CONTRACT_DOMAIN, contractUrl)
        } catch (e: InvalidUrlException) {

        }
    }

    /**
     * 切换本地聊天服务器地址
     */
    fun changeLocalChatServer(url: String) {
        try {
            if (AppPreference.CHAT_URL != url) {
                AppPreference.CHAT_URL = url
            }
            localChatUrl = url.toHttpUrl()
            manager.putDomain(ChatConst.CHAT_DOMAIN, localChatUrl)
        } catch (e: InvalidUrlException) {

        }
        // 通知连接层：选中的聊天服务器已变化，立即重建连接
        _chatServerChanged.value = System.currentTimeMillis()
    }


    /**
     * 切换企业服务器地址
     */
    fun changeOAServer(url: String) {
        try {
            if (AppPreference.OA_URL != url) {
                AppPreference.OA_URL = url
            }
            oaUrl = url.toHttpUrl()
            manager.putDomain(ChatConst.OA_DOMAIN, oaUrl)
        } catch (e: InvalidUrlException) {

        }
    }

    /**
     * 切换商城服务器地址
     */
    fun changeShopServer(url: String) {
        try {
            if (AppPreference.SHOP_URL != url) {
                AppPreference.SHOP_URL = url
            }
            shopUrl = url.toHttpUrl()
            manager.putDomain(ChatConst.SHOP_DOMAIN, shopUrl)
        } catch (e: InvalidUrlException) {

        }
    }

    /**
     * 是否选择了聊天服务器
     */
    fun hasChatServer(): Boolean {
        return localChatUrl.isNotEmpty()
    }

    /**
     * 是否选择了合约服务器
     */
    fun hasContractServer(): Boolean {
        return contractUrl.isNotEmpty()
    }

    /**
     * 是否具备使用app的条件
     */
    fun isOnline(): Boolean {
        return contractUrl.isNotEmpty() && localChatUrl.isNotEmpty()
    }
}