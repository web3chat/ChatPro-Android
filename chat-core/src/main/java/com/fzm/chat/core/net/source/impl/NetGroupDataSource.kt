package com.fzm.chat.core.net.source.impl

import com.fzm.chat.core.data.bean.GroupApplyTO
import com.fzm.chat.core.data.bean.GroupInfoTO
import com.fzm.chat.core.data.bean.GroupUserTO
import com.fzm.chat.core.data.bean.StringList
import com.fzm.chat.core.data.bean.toPO
import com.fzm.chat.core.data.po.GroupUserPO
import com.fzm.chat.core.net.api.GroupService
import com.fzm.chat.core.net.source.GroupDataSource
import com.zjy.architecture.data.Result
import com.zjy.architecture.ext.apiCall

/**
 * @author zhengjy
 * @since 2021/05/11
 * Description:
 */
class NetGroupDataSource(
    private val service: GroupService
) : GroupDataSource {

    override suspend fun createGroup(
        url: String,
        name: String,
        users: List<String>
    ): Result<GroupInfoTO> {
        return apiCall {
            service.createGroup(
                "$url${GroupService.API_CREATE_GROUP}", mapOf(
                    "name" to name,
                    "memberIds" to users
                )
            )
        }
    }

    override suspend fun editGroupAvatar(url: String?, gid: Long, avatar: String): Result<Any> {
        return apiCall {
            service.editGroupAvatar(
                "$url${GroupService.API_EDIT_GROUP_AVATAR}", mapOf(
                    "id" to gid,
                    "avatar" to avatar
                )
            )
        }
    }

    override suspend fun editGroupName(url: String?, gid: Long, name: String): Result<Any> {
        return apiCall {
            service.editGroupName(
                "$url${GroupService.API_EDIT_GROUP_NAME}", mapOf(
                    "id" to gid,
                    "name" to name
                )
            )
        }
    }

    override suspend fun editGroupNames(url: String?, gid: Long, name: String, publicName: String): Result<Any> {
        return apiCall {
            service.editGroupName(
                "$url${GroupService.API_EDIT_GROUP_NAME}", mapOf(
                    "id" to gid,
                    "name" to name,
                    "publicName" to publicName
                )
            )
        }
    }

    override suspend fun editNicknameInGroup(url: String?, gid: Long, name: String): Result<Any> {
        return apiCall {
            service.editNicknameInGroup(
                "$url${GroupService.API_EDIT_NICKNAME_IN_GROUP}", mapOf(
                    "id" to gid,
                    "memberName" to name
                )
            )
        }
    }

    override suspend fun inviteMembers(
        url: String?,
        gid: Long,
        members: List<String>
    ): Result<Any> {
        return apiCall {
            service.inviteMembers(
                "$url${GroupService.API_INVITE_MEMBERS}", mapOf(
                    "id" to gid,
                    "newMemberIds" to members
                )
            )
        }
    }

    override suspend fun removeMembers(
        url: String?,
        gid: Long,
        members: List<String>
    ): Result<StringList> {
        return apiCall {
            service.removeMembers(
                "$url${GroupService.API_REMOVE_MEMBERS}", mapOf(
                    "id" to gid,
                    "memberIds" to members
                )
            )
        }
    }

    override suspend fun getGroupUser(
        url: String?,
        gid: Long,
        address: String
    ): Result<GroupUserPO> {
        val result = apiCall {
            service.getGroupUser(
                "$url${GroupService.API_GROUP_USER_INFO}",
                mapOf("id" to gid, "memberId" to address)
            )
        }
        return if (result.isSucceed()) {
            Result.Success(result.data().toPO(gid))
        } else {
            Result.Error(result.error())
        }
    }

    override suspend fun getGroupUserList(url: String?, gid: Long): Result<List<GroupUserPO>> {
        val result = apiCall {
            service.getGroupUserList(
                "$url${GroupService.API_GROUP_USER_LIST}",
                mapOf("id" to gid)
            )
        }
        return if (result.isSucceed()) {
            Result.Success(result.data().members.map { it.toPO(gid) })
        } else {
            Result.Error(result.error())
        }
    }

    override suspend fun getGroupInfo(url: String?, gid: Long): Result<GroupInfoTO> {
        return apiCall {
            service.getGroupInfo(
                "$url${GroupService.API_GROUP_INFO}",
                mapOf("id" to gid)
            )
        }
    }

    override suspend fun getGroupPubInfo(
        url: String?,
        gid: Long,
    ): Result<GroupInfoTO> {
        return apiCall {
            service.getGroupPubInfo(
                "$url${GroupService.API_GROUP_PUB_INFO}",
                mapOf("id" to gid)
            )
        }
    }

    override suspend fun joinGroup(url: String?, gid: Long, inviterId: String?): Result<Any> {
        return apiCall {
            service.getGroupPubInfo(
                "$url${GroupService.API_JOIN_GROUP}",
                mapOf("id" to gid, "inviterId" to inviterId.orEmpty())
            )
        }
    }

    override suspend fun createGroupApply(
        url: String?,
        gid: Long,
        applyNote: String?
    ): Result<Any> {
        return apiCall {
            service.createGroupApply(
                "$url${GroupService.API_CREATE_GROUP_APPLY}",
                // 文档：id 为 string（群 ID），备注可选
                mapOf("id" to gid.toString(), "applyNote" to applyNote.orEmpty())
            )
        }
    }

    override suspend fun getGroupApplys(
        url: String?,
        gid: Long,
        count: Int,
        offset: Int
    ): Result<GroupApplyTO.Wrapper> {
        return apiCall {
            service.getGroupApplys(
                "$url${GroupService.API_GROUP_APPLY_LIST}",
                mapOf("id" to gid.toString(), "count" to count, "offset" to offset)
            )
        }
    }

    override suspend fun acceptGroupApply(
        url: String?,
        gid: Long,
        applyId: String
    ): Result<Any> {
        return apiCall {
            service.acceptGroupApply(
                "$url${GroupService.API_ACCEPT_GROUP_APPLY}",
                mapOf("applyId" to applyId, "id" to gid.toString())
            )
        }
    }

    override suspend fun rejectGroupApply(
        url: String?,
        gid: Long,
        applyId: String,
        reason: String?
    ): Result<Any> {
        return apiCall {
            service.rejectGroupApply(
                "$url${GroupService.API_REJECT_GROUP_APPLY}",
                mapOf(
                    "applyId" to applyId,
                    "id" to gid.toString(),
                    "rejectReason" to reason.orEmpty()
                )
            )
        }
    }

    override suspend fun getGroupList(url: String): Result<GroupInfoTO.Wrapper> {
        return apiCall { service.getGroupList("$url${GroupService.API_GROUP_LIST}") }
    }

    override suspend fun changeGroupOwner(url: String?, gid: Long, address: String): Result<Any> {
        return apiCall {
            service.changeGroupOwner(
                "$url${GroupService.API_CHANGE_OWNER}", mapOf(
                    "id" to gid, "memberId" to address
                )
            )
        }
    }

    override suspend fun changeGroupUserRole(
        url: String?,
        gid: Long,
        address: String,
        role: Int
    ): Result<Any> {
        return apiCall {
            service.changeGroupUserRole(
                "$url${GroupService.API_GROUP_ROLE}", mapOf(
                    "id" to gid, "memberId" to address, "memberType" to role
                )
            )
        }
    }

    override suspend fun changeFriendType(url: String?, gid: Long, friendType: Int): Result<Any> {
        return apiCall {
            service.changeFriendType(
                "$url${GroupService.API_FRIEND_TYPE}", mapOf(
                    "id" to gid, "friendType" to friendType
                )
            )
        }
    }

    override suspend fun changeJoinType(url: String?, gid: Long, joinType: Int): Result<Any> {
        return apiCall {
            service.changeJoinType(
                "$url${GroupService.API_JOIN_TYPE}", mapOf(
                    "id" to gid, "joinType" to joinType
                )
            )
        }
    }

    override suspend fun changeMuteType(url: String?, gid: Long, muteType: Int): Result<Any> {
        return apiCall {
            service.changeMuteType(
                "$url${GroupService.API_MUTE_TYPE}", mapOf(
                    "id" to gid, "muteType" to muteType
                )
            )
        }
    }

    override suspend fun changeMuteTime(
        url: String?,
        gid: Long,
        muteTime: Long,
        members: List<String>
    ): Result<GroupUserTO.Wrapper> {
        return apiCall {
            service.changeMuteTime(
                "$url${GroupService.API_MUTE_TIME}", mapOf(
                    "id" to gid, "muteTime" to muteTime, "memberIds" to members
                )
            )
        }
    }

    override suspend fun exitGroup(url: String?, gid: Long): Result<Any> {
        return apiCall {
            service.exitGroup(
                "$url${GroupService.API_GROUP_EXIT}",
                mapOf("id" to gid)
            )
        }
    }

    override suspend fun disbandGroup(url: String?, gid: Long): Result<Any> {
        return apiCall {
            service.disbandGroup(
                "$url${GroupService.API_GROUP_DISBAND}",
                mapOf("id" to gid)
            )
        }
    }
}