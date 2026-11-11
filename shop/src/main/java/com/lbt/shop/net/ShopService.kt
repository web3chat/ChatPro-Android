package com.lbt.shop.net

import com.fzm.chat.core.data.ChatConst
import com.zjy.architecture.net.HttpResult
import me.jessyan.retrofiturlmanager.RetrofitUrlManager
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * @author zhengjy
 * @since 2021/10/23
 * Description:
 */
interface ShopService {

    companion object {
        // type(2.APP 3.PC 4.微信小程序)
        const val DEVICE_HEADER = "type: 2"
    }

    /**
     * 根据聊天地址获取对应绑定的商城地址
     */
    @Headers(value = [
        RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.SHOP_DOMAIN,
        DEVICE_HEADER
    ])
    @POST("/mall/user/without/getAddress")
    suspend fun getShopAddress(@Body map: Map<String, String>): HttpResult<String>
}