package com.fzm.chat.utils

fun addrIsContained(addr: String): Boolean {
    val addrs = listOf<String>(
        "1Fiyuhhagt8XQZTrGQrksHExxi2HXNF62J",
        "15fV7wr4978eLhYGHU4M9sWJrL2eJY9WZf",
        "1PVqP8GQ1hgYJiDSsGe4u8zUcE9Rqdyii6",
    )

// 判断addr是否在列表中
    val isContained = addrs.contains(addr)
    return isContained
}