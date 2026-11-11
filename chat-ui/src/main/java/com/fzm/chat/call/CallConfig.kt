package com.fzm.chat.call

import android.net.Uri
import com.fzm.chat.core.data.ChatConfig

/**
 * 通话房间（dtalk H5，基于 LiveKit）服务配置
 *
 * App 经 POST /api/v1/room/create 建房拿到 房间号+PIN，再拼链接发送给聊天对象；
 * 页面侧进房需要的 name（昵称）由调用方在链接上追加。
 */
object CallConfig {

    /**
     * 通话 H5 服务地址，来自本机 chat-pro.properties 的 `CALL_SERVER`
     *
     * 仓库不保存真实地址；留空时通话相关入口不可用。
     * 链接格式示例：`{BASE_URL}/?room=h5_voice_xxx&pin=7514&mode=voice&name=xxx#/`
     */
    val BASE_URL: String
        get() = ChatConfig.CALL_SERVER

    /**
     * 是否放行自签名证书（仅局域网 / 内网部署场景使用）
     *
     * dtalk 使用正式 HTTPS 证书，这里必须为 false；
     * 如后续改回自签名内网服务，再按需改为 true。
     */
    val TRUST_SELF_SIGNED: Boolean get() = false

    /** 通话服务主机名（自签名证书放行时限定只对该域名生效） */
    fun host(): String = Uri.parse(BASE_URL).host.orEmpty()

    /**
     * 拼接房间加入链接
     */
    fun roomUrl(room: String, pin: String, mode: String): String {
        return "$BASE_URL/?room=$room&pin=$pin&mode=$mode#/"
    }
}
