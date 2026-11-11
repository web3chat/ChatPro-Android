package com.fzm.chat.core.data

/**
 * @author zhengjy
 * @since 2020/12/15
 * Description:
 */
object ChatConst {

    /**
     * 中心化服务器标识符
     */
    const val CENTRALIZED_DOMAIN = "CENTRALIZED"
    const val GO_NEW_DOMAIN = "GO_NEW"

    /**
     * 分布式合约服务器标识符
     */
    const val CONTRACT_DOMAIN = "CONTRACT"

    /**
     * 钱包合约服务器标识符
     */
    const val WALLET_DOMAIN = "WALLET"
    const val PHP_DOMAIN = "PHP"

    /**
     * 企业服务器标识符
     */
    const val OA_DOMAIN = "OA"

    /**
     * 商城后端服务器标识符
     */
    const val SHOP_DOMAIN = "SHOP"

    /**
     * 聊天服务器标识符
     */
    const val CHAT_DOMAIN = "CHAT"

    object UserField {
        const val NICKNAME = "nickname"
        const val AVATAR = "avatar"
        const val PUB_KEY = "pubKey"
        @Deprecated("合约不存")
        const val PHONE = "phone"
        @Deprecated("合约不存")
        const val EMAIL = "email"

        const val CHAIN_PREFIX = "chain."

        /** 收款地址同步成功标记的 MMKV key 前缀（实际 key 为该前缀拼接用户地址） */
        const val CHAIN_SYNC_PREFIX = "chain_sync_ok_"
    }

    const val REGEX_CHINESE = "[\u4e00-\u9fa5]+"

    //A前面要有个空格，注意
    const val REGEX_ENGLISH = "^[ A-Za-z]*$"

    //A前面要有个空格，注意
    const val REGEX_CHINESE_ENGLISH = "^[\u4e00-\u9fa5 A-Za-z]*$"

    const val PHONE_PATTERN = "^1\\d{10}\$"

    object Error {

        /**
         * 解密失败
         */
        const val DECRYPT_ERROR = 1000

        /**
         * 加密失败
         */
        const val ENCRYPT_ERROR = 1001

        /**
         * 创建助记词失败
         */
        const val CREATE_WORDS_ERROR = 1002

        /**
         * 保存助记词失败
         */
        const val SAVE_WORDS_ERROR = 1003
    }

    /**
     * 不支持的消息类型
     */
    const val UNSUPPORTED_MSG_TYPE = -1

    /**
     * 阅后即焚占位气泡本地 type，不与 Biz.MsgType 冲突
     */
    const val BURN_PLACEHOLDER_TYPE = 1001

    /**
     * 通话房间邀请卡片本地 type，不与 Biz.MsgType 冲突
     */
    const val CALL_INVITE_TYPE = 1002

    /**
     * 阅后即焚焚毁通知文案（气泡与预览共用）
     */
    const val BURNED_NOTIFICATION_TEXT = "已阅后焚毁"

    /**
     * 会话类型：私聊
     */
    const val PRIVATE_CHANNEL = 0

    /**
     * 会话类型：群聊
     */
    const val GROUP_CHANNEL = 1

    /**
     * 账户类型
     */
    const val PHONE = 1
    const val EMAIL = 2

    /**
     * 服务器类型
     */
    const val CHAT_SERVER = 1
    const val CONTRACT_SERVER = 2

    /**
     * 转账收款类型
     */
    const val TRANSFER = 1
    @Deprecated("目前只有转账功能")
    const val RECEIPT = 2

    const val ENC_PREFIX = "\$ENC\$"

    /** URL 编码后的加密文件标记（"%24ENC%24"），兼容 iOS 生成的下载地址 */
    const val ENC_PREFIX_ENCODED = "%24ENC%24"

    const val AT_ALL_MEMBERS = "ALL"

    /**
     * 红包类型：单人红包
     */
    const val RED_PACKET_SINGLE = 0
    /**
     * 红包类型：拼手气红包
     */
    const val RED_PACKET_LUCKY = 1
    /**
     * 红包类型：固定金额红包
     */
    const val RED_PACKET_FAIR = 2

    const val INVALID_AES_KEY = "invalid_aes_key"

    /**
     * 永久禁言时间
     */
    const val MUTE_FOREVER = 9223372036854775807L
}