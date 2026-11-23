package com.fzm.chat
import com.fzm.chat.WConfig

class W {
    companion object {
      val appType = WConfig.APP_CHAT33PRO
      // 手机号登录版本：首页只保留手机号登录，不展示助记词/私钥导入
      const val PHONE_LOGIN_ONLY = true
    }

}