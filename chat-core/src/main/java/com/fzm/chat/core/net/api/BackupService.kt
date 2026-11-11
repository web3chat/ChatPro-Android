package com.fzm.chat.core.net.api

import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.BackupMnemonic
import com.fzm.chat.core.data.bean.BindAccount
import com.fzm.chat.core.data.bean.CheckSE
import com.fzm.chat.core.data.bean.GetShard
import com.zjy.architecture.net.HttpResult
import me.jessyan.retrofiturlmanager.RetrofitUrlManager
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * @author zhengjy
 * @since 2021/01/13
 * Description:
 */
@JvmSuppressWildcards
interface BackupService {

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/phone-send")
    suspend fun sendPhoneCodeV2(@Body map: Map<String, Any>): HttpResult<Any>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/email-send")
    suspend fun sendEmailCodeV2(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/phone-retrieve")
    suspend fun fetchBackupByPhoneV2(@Body map: Map<String, Any>): HttpResult<BackupMnemonic>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/email-retrieve")
    suspend fun fetchBackupByEmailV2(@Body map: Map<String, Any>): HttpResult<BackupMnemonic>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/address-retrieve")
    suspend fun fetchBackupByAddress(): HttpResult<BackupMnemonic>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/phone-binding")
    suspend fun bindPhoneV2(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/email-binding")
    suspend fun bindEmailV2(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/phone-export")
    suspend fun phoneExport(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/v2/email-export")
    suspend fun emailExport(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/phone-query")
    suspend fun phoneQuery(@Body map: Map<String, Any>): HttpResult<Map<String, *>>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/email-query")
    suspend fun emailQuery(@Body map: Map<String, Any>): HttpResult<Map<String, *>>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/edit-mnemonic")
    suspend fun updateMnemonic(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/get-address")
    suspend fun getAddress(@Body map: Map<String, Any>): HttpResult<BackupMnemonic>


    /**------------------------------------已废弃v1接口------------------------------------**/
    @Deprecated("使用v2接口")
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/phone-send")
    suspend fun sendPhoneCode(@Body map: Map<String, Any>): HttpResult<Any>

    @Deprecated("使用v2接口")
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/email-send")
    suspend fun sendEmailCode(@Body map: Map<String, Any>): HttpResult<Any>

    @Deprecated("使用v2接口")
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/email-binding")
    suspend fun bindEmail(@Body map: Map<String, Any>): HttpResult<Any>

    @Deprecated("使用v2接口")
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/phone-binding")
    suspend fun bindPhone(@Body map: Map<String, Any>): HttpResult<Any>

    @Deprecated("使用v2接口")
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/phone-retrieve")
    suspend fun fetchBackupByPhone(@Body map: Map<String, Any>): HttpResult<BackupMnemonic>

    @Deprecated("使用v2接口")
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.CENTRALIZED_DOMAIN])
    @POST("/backup/email-retrieve")
    suspend fun fetchBackupByEmail(@Body map: Map<String, Any>): HttpResult<BackupMnemonic>



    // NEW API FOR S1 S2
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/send-code")
    suspend fun newSendPhoneCode(@Body map: Map<String, Any>): HttpResult<Any>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/store-shard")
    suspend fun storeShard(@Body map: Map<String, Any>): HttpResult<Any>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/get-shard")
    suspend fun getShard(@Body map: Map<String, Any>): HttpResult<GetShard>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/check-shard-exists")
    suspend fun checkShardExists(@Body map: Map<String, Any>): HttpResult<CheckSE>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/reset-shard")
    suspend fun resetShard(@Body map: Map<String, Any>): HttpResult<Any>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/bind-account")
    suspend fun bindAccount(@Body map: Map<String, Any>): HttpResult<BindAccount>
    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/query-chataddress")
    suspend fun queryChataddress(@Body map: Map<String, Any>): HttpResult<BindAccount>

    @Headers(value = [RetrofitUrlManager.DOMAIN_NAME_HEADER + ChatConst.GO_NEW_DOMAIN])
    @POST("/api/v1/query-accounts")
    suspend fun queryAccounts(@Body map: Map<String, Any>): HttpResult<BindAccount>
}