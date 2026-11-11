package com.lbt.shop.net

import com.zjy.architecture.data.Result

/**
 * @author zhengjy
 * @since 2021/10/23
 * Description:
 */
interface ShopDataSource {

    /**
     * 根据聊天地址获取对应绑定的商城地址
     */
    suspend fun getShopAddress(outAddress: String): Result<String>
}