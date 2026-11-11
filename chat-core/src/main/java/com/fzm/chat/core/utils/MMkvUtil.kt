package com.fzm.chat.core.utils

import android.os.Parcelable
import com.fzm.wallet.sdk.base.BWallet
import com.tencent.mmkv.MMKV
import java.util.Collections.emptySet

object MMkvUtil {

    private val mmkv = MMKV.mmkvWithID(BWallet)

    fun encode(key: String, value: Any?) {
        when (value) {
            is String -> mmkv?.encode(key, value)
            is Float -> mmkv?.encode(key, value)
            is Boolean -> mmkv?.encode(key, value)
            is Int -> mmkv?.encode(key, value)
            is Long -> mmkv?.encode(key, value)
            is Double -> mmkv?.encode(key, value)
            is ByteArray -> mmkv?.encode(key, value)
            else -> return
        }
    }

    fun <T : Parcelable> encode(key: String, t: T?) {
        if (t == null) {
            return
        }
        mmkv?.encode(key, t)
    }

    fun encode(key: String, sets: Set<String>?) {
        if (sets == null) {
            return
        }
        mmkv.encode(key, sets)
    }

    fun decodeInt(key: String): Int {
        return mmkv.decodeInt(key, 0)
    }

    fun decodeDouble(key: String): Double {
        return mmkv.decodeDouble(key, 0.00)
    }

    fun decodeLong(key: String): Long {
        return mmkv.decodeLong(key, 0L)
    }

    fun decodeBoolean(key: String): Boolean {
        return mmkv.decodeBool(key, false)
    }

    fun decodeFloat(key: String): Float {
        return mmkv.decodeFloat(key, 0F)
    }

    fun decodeFloat(key: String, default: Float): Float {
        return mmkv.decodeFloat(key, default)
    }

    fun decodeByteArray(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    fun decodeString(key: String): String {
        return mmkv.decodeString(key, "") ?: ""
    }

    fun decodeString(key: String, default: String): String {
        return mmkv.decodeString(key, default) ?: ""
    }

    fun <T : Parcelable> decodeParcelable(key: String, tClass: Class<T>): T? {
        return mmkv.decodeParcelable(key, tClass)
    }

    fun decodeStringSet(key: String): Set<String> {
        return mmkv.decodeStringSet(key, emptySet()) ?: emptySet()
    }

    fun containsKey(key: String): Boolean {
        return mmkv?.containsKey(key) == true
    }

    fun removeKey(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun clearAll() {
        mmkv.clearAll()
    }
}