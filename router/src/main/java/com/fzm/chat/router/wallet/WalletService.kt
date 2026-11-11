package com.fzm.chat.router.wallet

import com.alibaba.android.arouter.facade.template.IProvider
import com.fzm.chat.router.main.SimpleTx
import com.fzm.chat.router.redpacket.ModuleAsset

/**
 * @author zhengjy
 * @since 2021/08/13
 * Description:
 */
interface WalletService : IProvider {

    /**
     * 导入助记词生成钱包
     */
    @Throws(Exception::class)
    suspend fun importMnem(user: String, mnem: String, name: String, password: String,phone:String,email:String)

    /**
     * 关闭钱包
     */
    suspend fun closeWallet()

    /**
     * 获取获取默认手续费
     */
    fun getDefaultFee(): Double

    /**
     * 根据hash查询交易详情
     *
     * @param chain     主链
     * @param hash      交易hash
     * @param symbol    币种symbol
     */
    suspend fun getTransactionByHash(chain: String, platform:String,hash: String, symbol: String): Result<SimpleTx>

    /**
     * 根据地址查询钱包中的token资产
     *
     * @param address   红包资产的地址
     */
    suspend fun getModuleAssets(address: String): List<ModuleAsset>

    /**
     * 上传更新用户主链地址
     *
     * @param address   主链地址
     */
    suspend fun updateChainAddress(address: Map<String, String>?): String

    /**
     * 查询收款地址同步状态（本机各链地址 vs 链上资料已登记地址的差异），用于排查“找不到对方xx地址”问题
     *
     * @return 诊断文本：本机地址、链上已登记地址、缺失/不一致清单
     */
    suspend fun queryChainAddressSyncState(): String
}