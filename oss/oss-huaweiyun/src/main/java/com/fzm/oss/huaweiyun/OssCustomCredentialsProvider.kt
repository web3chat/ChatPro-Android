package com.fzm.oss.huaweiyun

import android.annotation.SuppressLint
import com.obs.services.IObsCredentialsProvider
import com.obs.services.exception.ObsException
import com.obs.services.internal.security.LimitedTimeSecurityKey
import com.obs.services.model.ISecurityKey
import com.zjy.architecture.di.rootScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat

class OssCustomCredentialsProvider(private val stsServer: String) : IObsCredentialsProvider {

    private val client by rootScope.inject<OkHttpClient>()

    // 说明：STS 服务返回的结构为
    // { "result": 0, "data": { "Credentials": { "AccessKeyId", "AccessKeySecret",
    //   "SecurityToken", "Expiration" }, "AssumedRoleUser": {...} } }
    // 真实 AK/SK 属于凭证，请勿写进代码或注释（仓库不再保留任何示例密钥）。

    override fun setSecurityKey(p0: ISecurityKey?) {
        throw Exception("you cannot set security key")
    }

    @SuppressLint("SimpleDateFormat")
    override fun getSecurityKey(): ISecurityKey {

        val authToken: LimitedTimeSecurityKey
        return try {
            val request = Request.Builder().url(stsServer).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val authData = response.body()?.string()
                if (authData.isNullOrEmpty()) {
                    throw ObsException("obs auth empty response")
                }
                val jsonObj = JSONObject(authData)
                val statusCode = jsonObj.getInt("result")
                authToken = if (statusCode == 0) {
                    val data = jsonObj.getJSONObject("data")
                    val credential = data.getJSONObject("Credentials")
                    val ak = credential.getString("AccessKeyId")
                    val sk = credential.getString("AccessKeySecret")
                    val token = credential.getString("SecurityToken")
                    val expiration = credential.getString("Expiration")
                    LimitedTimeSecurityKey(
                        ak,
                        sk,
                        token,
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(expiration)
                    )
                } else {
                    val errorMessage = jsonObj.getString("message")
                    throw ObsException("ErrorCode: $statusCode| ErrorMessage: $errorMessage")
                }
            } else {
                throw ObsException("request fail")
            }
            authToken
        } catch (e: Exception) {
            throw ObsException("obs exception", e)
        }
    }
}