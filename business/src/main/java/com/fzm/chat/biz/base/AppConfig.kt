package com.fzm.chat.biz.base

import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.biz.BuildConfig
import com.fzm.chat.core.data.ChatConfig.DOWNLOAD_URL
import com.zjy.architecture.util.AbstractConfig

/**
 * @author zhengjy
 * @since 2020/12/14
 * Description:
 */
object GlobalConfig {

    /**
     * 是否开启调试模式，日志等
     */
    val DEBUG = BuildConfig.DEBUG

    /**
     * 本地日志加密公钥
     */
    const val LOG_ENC_KEY =
        "76f5a32b08ae06956af61dse930f557d438c474f486b88424ddfb908c62ddfc0ff2b06183a089a6d046720f349ab883a6af0f15545beec4ad510d672"
}

object AppConfig {

    /**
     * App基本配置
     */
    object AppBaseConfig : AbstractConfig("base.properties")


    /**
     * App正式环境配置
     */
    object AppProConfig : AbstractConfig("pro.properties")

    /*--------------------------------------App基本配置-------------------------------------------*/
    //private val config = if (BuildConfig.DEVELOP) AppDevConfig else AppProConfig
    private val config = AppProConfig

    val APP_ACCENT_COLOR_STR = AppBaseConfig["APP_ACCENT_COLOR_STR"]

    val APP_NAME = AppBaseConfig["APP_NAME"]
    val APP_NAME_EN = AppBaseConfig["APP_NAME_EN"]

    val APP_SCHEME = AppBaseConfig["APP_SCHEME"]
    val APP_HOST = AppBaseConfig["APP_HOST"]

    /**
     * 币种放大倍数，默认1e8
     */
    val AMOUNT_SCALE = AppBaseConfig["AMOUNT_SCALE"].toInt()



    /**
     * App分享页面
     */
    val APP_DOWNLOAD_URL = DOWNLOAD_URL



    /**
     * 浏览器表识符
     */
    const val USER_AGENT = ";FZM-3SYXIN;"
}

/**
 * 获取指定地址的分享二维码链接
 */
fun AppConfig.shareCode(address: String?): String = "$APP_DOWNLOAD_URL/?uid=$address"

/**
 * 获取指定群地址的分享二维码链接
 */
fun AppConfig.shareGroupCode(
    gid: Long,
    server: String,
    inviterId: String?,
    createTime: Long
): String =
    "$APP_DOWNLOAD_URL/group-code?gid=$gid&server=$server&inviterId=${inviterId.orEmpty()}&createTime=$createTime"

/**
 * 获取指定地址的转账二维码链接
 */
fun AppConfig.transferCode(
    target: String?,
    address: String?,
    chain: String,
    platform: String
): String = "$APP_DOWNLOAD_URL/?transfer_target=$target&address=$address&chain=$chain&platform=$platform"