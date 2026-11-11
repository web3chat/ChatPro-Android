package com.fzm.chat.core.net

import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.ext.handleException
import com.zjy.architecture.net.HttpResult
import kotlinx.coroutines.CancellationException


suspend fun <T> nApiCall(code: Int = com.zjy.architecture.ext.SUCCESS_CODE, call: suspend () -> HttpResult<T>): Result<T> {
    return try {
        call().let {
            Result.Success(it.data)
            /*
            if (it.code == code) {
                Result.Success(it.data)
            } else {
                Result.Error(ApiException(it.code, it.message))
            }*/
        }
    } catch (e: Exception) {
        if (e is CancellationException) {
            // do nothing
            throw e
        } else {
            Result.Error(handleException(e))
        }
    }
}