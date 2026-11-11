package com.lbt.shop.net

import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.apiCall

/**
 * @author zhengjy
 * @since 2021/10/23
 * Description:
 */
class ShopRepository(private val service: ShopService) : ShopDataSource {

    override suspend fun getShopAddress(outAddress: String): Result<String> {
        return apiCall { service.getShopAddress(mapOf("outAddress" to outAddress)) }
    }
}