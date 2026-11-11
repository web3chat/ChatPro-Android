package com.fzm.chat.core.net.source.impl

import com.fzm.chat.core.data.bean.BackupMnemonic
import com.fzm.chat.core.data.bean.BindAccount
import com.fzm.chat.core.data.bean.CheckSE
import com.fzm.chat.core.data.bean.GetShard
import com.fzm.chat.core.data.enum.CodeType
import com.fzm.chat.core.net.api.BackupService
import com.fzm.chat.core.net.nApiCall
import com.fzm.chat.core.net.source.BackupDataSource
import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.apiCall

/**
 * @author zhengjy
 * @since 2021/01/13
 * Description:
 */
class NetBackupDataSource(
    private val service: BackupService
) : BackupDataSource {

    override suspend fun sendPhoneCode(phone: String): Result<Any> {
        return apiCall { service.sendPhoneCode(mapOf("phone" to phone)) }
    }

    override suspend fun sendEmailCode(email: String): Result<Any> {
        return apiCall { service.sendEmailCode(mapOf("email" to email)) }
    }

    override suspend fun sendPhoneCodeV2(phone: String, codeType: CodeType): Result<Any> {
        return apiCall {
            service.sendPhoneCodeV2(
                mapOf(
                    "phone" to phone,
                    "codeType" to codeType.value
                )
            )
        }
    }

    override suspend fun newSendPhoneCode(phone: String, codeType: CodeType): Result<Any> {
        return apiCall {
            service.newSendPhoneCode(
                mapOf(
                    "phone" to phone,
                    "purpose" to codeType.value
                )
            )
        }
    }

    override suspend fun storeShard(
        phone: String,
        code: String,
        chataddress: String,
        shard_key: String
    ): Result<Any> {
        return apiCall {
            service.storeShard(
                mapOf(
                    "phone" to phone,
                    "code" to code,
                    "chataddress" to chataddress,
                    "shard_key" to shard_key
                )
            )
        }
    }

    override suspend fun resetShard(
        phone: String,
        code: String,
        chataddress: String,
        shard_key: String
    ): Result<Any> {
        return apiCall {
            service.resetShard(
                mapOf(
                    "phone" to phone,
                    "code" to code,
                    "chataddress" to chataddress,
                    "shard_key" to shard_key
                )
            )
        }
    }

    override suspend fun bindAccount(
        phone: String,
        wechat_id: String,
        xiaohongshu_id: String,
        douyin_id: String,
        chataddress: String
    ): Result<BindAccount> {
        return apiCall {
            val paramMap = mutableMapOf<String, String>()

            if (phone.isNotEmpty()) {
                paramMap["phone"] = phone
            }
            if (wechat_id.isNotEmpty()) {
                paramMap["wechat_id"] = wechat_id
            }
            if (xiaohongshu_id.isNotEmpty()) {
                paramMap["xiaohongshu_id"] = xiaohongshu_id
            }
            if (douyin_id.isNotEmpty()) {
                paramMap["douyin_id"] = douyin_id
            }
            if (chataddress.isNotEmpty()) {
                paramMap["chataddress"] = chataddress
            }
            service.bindAccount(paramMap)

        }
    }

    override suspend fun queryChataddress(
        keyword: String,
    ): Result<BindAccount> {
        return apiCall {
            service.queryChataddress(
                mapOf(
                    "keyword" to keyword
                )
            )
        }
    }
    override suspend fun queryAccounts(
        chataddress: String
    ): Result<BindAccount> {
        return apiCall {
            service.queryAccounts(
                mapOf(
                    "chataddress" to chataddress
                )
            )
        }
    }

    override suspend fun getShard(phone: String, code: String): Result<GetShard> {
        return nApiCall { service.getShard(mapOf("phone" to phone, "code" to code)) }
    }

    override suspend fun checkShardExists(phone: String): Result<CheckSE> {
        return nApiCall { service.checkShardExists(mapOf("phone" to phone)) }
    }

    override suspend fun sendEmailCodeV2(email: String, codeType: CodeType): Result<Any> {
        return apiCall {
            service.sendEmailCodeV2(
                mapOf(
                    "email" to email,
                    "codeType" to codeType.value
                )
            )
        }
    }

    override suspend fun fetchBackupByPhone(phone: String, code: String): Result<BackupMnemonic> {
        return apiCall { service.fetchBackupByPhoneV2(mapOf("phone" to phone, "code" to code)) }
    }

    override suspend fun fetchBackupByEmail(email: String, code: String): Result<BackupMnemonic> {
        return apiCall { service.fetchBackupByEmailV2(mapOf("email" to email, "code" to code)) }
    }

    override suspend fun fetchBackupByAddress(): Result<BackupMnemonic> {
        return apiCall { service.fetchBackupByAddress() }
    }

    override suspend fun bindPhone(phone: String, code: String, mnemonic: String): Result<Any> {
        val map = mapOf(
            "phone" to phone,
            "code" to code,
            "mnemonic" to mnemonic
        )
        return apiCall { service.bindPhone(map) }
    }

    override suspend fun bindEmail(email: String, code: String, mnemonic: String): Result<Any> {
        val map = mapOf(
            "email" to email,
            "code" to code,
            "mnemonic" to mnemonic
        )
        return apiCall { service.bindEmail(map) }
    }

    override suspend fun bindPhoneV2(phone: String, code: String, mnemonic: String): Result<Any> {
        val map = mapOf(
            "phone" to phone,
            "code" to code,
            "mnemonic" to mnemonic
        )
        return apiCall { service.bindPhoneV2(map) }
    }

    override suspend fun bindEmailV2(email: String, code: String, mnemonic: String): Result<Any> {
        val map = mapOf(
            "email" to email,
            "code" to code,
            "mnemonic" to mnemonic
        )
        return apiCall { service.bindEmailV2(map) }
    }

    override suspend fun phoneExport(phone: String, code: String, address: String): Result<Any> {
        val map = mapOf(
            "phone" to phone,
            "code" to code,
            "address" to address
        )
        return apiCall { service.phoneExport(map) }
    }

    override suspend fun emailExport(email: String, code: String, address: String): Result<Any> {
        val map = mapOf(
            "email" to email,
            "code" to code,
            "address" to address
        )
        return apiCall { service.emailExport(map) }
    }

    override suspend fun phoneQuery(phone: String): Result<Map<String, *>> {
        return apiCall { service.phoneQuery(mapOf("phone" to phone)) }
    }

    override suspend fun emailQuery(email: String): Result<Map<String, *>> {
        return apiCall { service.emailQuery(mapOf("email" to email)) }
    }

    override suspend fun updateMnemonic(mnemonic: String): Result<Any> {
        return apiCall { service.updateMnemonic(mapOf("mnemonic" to mnemonic)) }
    }

    override suspend fun getAddress(query: String): Result<BackupMnemonic> {
        return apiCall { service.getAddress(mapOf("query" to query)) }
    }
}