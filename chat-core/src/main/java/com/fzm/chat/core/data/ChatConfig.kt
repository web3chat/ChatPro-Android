package com.fzm.chat.core.data

import com.fzm.chat.W
import com.fzm.chat.WConfig
import com.fzm.chat.core.BuildConfig
import com.zjy.architecture.util.AbstractConfig

/**
 * @author zhengjy
 * @since 2020/12/14
 * Description:
 */
object ChatConfig {

    private object ChatPro : AbstractConfig("chat-pro.properties")

    //val config: AbstractConfig = if (BuildConfig.DEVELOP) ChatDev else ChatPro
    val config: AbstractConfig = ChatPro


    //PHP
    val BASE_URL =
        if (W.appType == WConfig.APP_TTT) {
            config["BASE_URL_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["BASE_URL_NCHAT"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["BASE_URL_CIP"]
        } else {
            config["BASE_URL"]
        }

    //GO
    val GO_URL =
        if (W.appType == WConfig.APP_TTT) {
            config["GO_URL_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["GO_URL_NCHAT"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["GO_URL_CIP"]
        } else {
            config["GO_URL"]
        }

    /**
     * 中心化服务器地址，用于支持发现服务，更新等接口
     */
    val CENTRALIZED_SERVER =
        if (W.appType == WConfig.APP_TTT) {
            config["CENTRALIZED_SERVER_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["CENTRALIZED_SERVER_NCHAT"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["CENTRALIZED_SERVER_CIP"]
        } else {
            config["CENTRALIZED_SERVER"]
        }
    val GO_NEW_SERVER =
        if (W.appType == WConfig.APP_TTT) {
            config["GO_NEW_SERVER_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["GO_NEW_SERVER_NCHAT"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["GO_NEW_SERVER_CIP"]
        } else {
            config["GO_NEW_SERVER"]
        }

    //business app分享页面
    val DOWNLOAD_URL =
        if (W.appType == WConfig.APP_TTT) {
            config["DOWNLOAD_URL_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["DOWNLOAD_URL_TEST"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["DOWNLOAD_URL_CIP"]
        } else {
            config["DOWNLOAD_URL"]
        }

    /**
     * 扫码登录服务器地址（PC 网页版扫码登录）
     */
    val QR_LOGIN_SERVER =
        if (W.appType == WConfig.APP_TTT) {
            config["QR_LOGIN_SERVER_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["QR_LOGIN_SERVER_NCHAT"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["QR_LOGIN_SERVER_CIP"]
        } else {
            config["QR_LOGIN_SERVER"]
        }

    /**
     * 扫码登录 ECDH v2 开关（安全升级，见《qr-login-ecdh-app-guide.md》，2026-10-15）
     *
     * false（默认，chat-pro.properties 中 QR_LOGIN_ECDH=false）：
     *   只支持 v1 二维码（key+iv 在二维码里），扫到 qr-login-v2 按“无法识别”处理，
     *   即完全回到 ECDH 改造之前的老逻辑；
     * true：同时支持 v1/v2，v2 用 PC 临时公钥 + APP 临时密钥对 ECDH 派生密钥加密私钥。
     *
     * 灰度顺序：服务端已上线 → APP 先开开关发版 → PC 端再切 v2 二维码。
     */
    val QR_LOGIN_ECDH = config["QR_LOGIN_ECDH"].toBoolean()

    /**
     * 私聊聊天记录本地化开关（2026-11-03）
     *
     * true（默认，chat-pro.properties 中 PRI_CHAT_LOCAL=true）：
     *   私聊与群聊一致，打开会话从本地 Room DB 分页加载（秒开），不再等待服务端；
     *   同时后台静默拉取服务端最近记录，用于补齐本地缺失消息和撤回对账。
     * false：保持服务端分页拉取模式（/store/app/pri-chat-record）。
     */
    val PRI_CHAT_LOCAL = config["PRI_CHAT_LOCAL"].toBoolean()

    /**
     * 崩溃兜底开关（2026-11-03）
     *
     * true（默认）：主进程崩溃时拉起错误展示页（CrashReportActivity，运行在 :crash 进程），
     *              用户可截图/复制堆栈；图片、视频、文件等入口点击异常也会弹框而不是闪退。
     * false：关闭兜底，崩溃交给系统默认处理。
     */
    val CRASH_DIALOG = config["CRASH_DIALOG"].toBoolean()

    /**
     * 启动时预请求相册（存储）权限（2026-11-03）
     *
     * true（默认）：首次进入主界面时申请一次存储权限，避免进聊天页点「图片/视频」才首次申请；
     *              只申请一次，拒绝不影响使用，聊天页入口仍会再次申请。
     */
    val AUTO_REQUEST_STORAGE = config["AUTO_REQUEST_STORAGE"].toBoolean()

    /**
     * 谈信广场服务器地址（发帖、评论、公众号等接口）
     *
     * debug 构建（联调）优先用 `SQUARE_SERVER_DEBUG`（chat-pro.properties）
     * 指向测试服务器；release 始终用正式地址。留空则 debug 也回落正式地址。
     */
    val SQUARE_SERVER: String =
        if (BuildConfig.DEBUG && !config["SQUARE_SERVER_DEBUG"].isNullOrBlank()) {
            config["SQUARE_SERVER_DEBUG"]
        } else if (W.appType == WConfig.APP_TTT) {
            config["SQUARE_SERVER_TEST"]
        } else if (W.appType == WConfig.APP_NCHAT) {
            config["SQUARE_SERVER_NCHAT"]
        } else if (W.appType == WConfig.APP_CIP) {
            config["SQUARE_SERVER_CIP"]
        } else {
            config["SQUARE_SERVER"]
        }

    /**
     * 广场接口路径前缀（反向代理暴露的后缀）
     *
     * 默认 `/square/app`；反代换成其它后缀时只改 `chat-pro.properties` 的 `SQUARE_PATH`，无需改代码。
     */
    val SQUARE_PATH: String =
        config["SQUARE_PATH"]?.takeIf { it.isNotBlank() } ?: "/square/app"


    /**
     * 每页数据数量
     */
    val PAGE_SIZE = config["PAGE_SIZE"].toInt()

    /**
     * 最大语音录音时长
     */
    val MAX_AUDIO_DURATION = config["MAX_AUDIO_DURATION"].toInt()

    /**
     * DeepSeek AI 的 API Key（chat-pro.properties 的 DEEPSEEK_API_KEY）
     *
     * 仓库里不保存真实 Key：留空时 AI 翻译/助手不可用，需要在本机 chat-pro.properties 里配置。
     */
    val DEEPSEEK_API_KEY: String = config["DEEPSEEK_API_KEY"]?.trim().orEmpty()

    /**
     * 通话房间（dtalk H5，基于 LiveKit）服务地址
     *
     * 对应 chat-pro.properties 的 `CALL_SERVER`；仓库不保存真实地址，留空则通话入口不可用。
     */
    val CALL_SERVER: String = config["CALL_SERVER"]?.trim().orEmpty()

    /**
     * 代扣地址私钥
     */
    val NO_BALANCE_PRIVATE_KEY = config["NO_BALANCE_PRIVATE_KEY"]

    /**
     * 是否启用消息加密
     */
    val ENCRYPT = config["ENCRYPT"].toBoolean()

    /**
     * 是否启用文件加密
     */
    val FILE_ENCRYPT = config["FILE_ENCRYPT"].toBoolean()

    /**
     * 重新编辑超时时间（撤回多久以后不能重新编辑）
     */
    const val REEDIT_TIMEOUT = 10 * 60 * 1000L

    /**
     * 撤回提示自动删除倒计时：1小时
     *
     * 撤回后展示的"你撤回了一条消息/对方消息/成员消息"提示，倒计时1小时后自动删除，
     * 倒计时不展示到界面。
     */
    const val REVOKE_TIP_EXPIRE = 60 * 60 * 1000L

    /**
     * 当前聊天对象
     */
    var CURRENT_TARGET: String? = null
}