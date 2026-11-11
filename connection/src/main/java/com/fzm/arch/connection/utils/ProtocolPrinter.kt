package com.fzm.arch.connection.utils

import chat33.comet.Socket
import com.alibaba.android.arouter.facade.template.IProvider

/**
 * @author zhengjy
 * @since 2022/07/28
 * Description:
 */
interface ProtocolPrinter : IProvider {

    fun print(proto: Socket.Proto): String
}