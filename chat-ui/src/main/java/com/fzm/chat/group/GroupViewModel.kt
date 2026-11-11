package com.fzm.chat.group

import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.map
import com.fzm.arch.connection.socket.ConnectionManager
import com.fzm.chat.core.data.ChatDatabase
import com.fzm.chat.core.data.ChatDatabaseProvider
import com.fzm.chat.core.data.bean.Contact
import com.fzm.chat.core.data.bean.GroupApplyTO
import com.fzm.chat.core.data.bean.GroupInfoTO
import com.fzm.chat.core.data.model.GroupUser
import com.fzm.chat.core.data.po.GroupInfo
import com.fzm.chat.core.repo.GroupRepository
import com.fzm.chat.core.session.LoginDelegate
import com.fzm.chat.core.utils.toLikeKey
import com.fzm.chat.router.oss.MediaType
import com.fzm.chat.router.oss.OssModule
import com.fzm.chat.router.oss.OssService
import com.fzm.chat.router.route
import com.zjy.architecture.data.Result
import com.zjy.architecture.exception.ApiException
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request
import com.zjy.architecture.util.isContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * @author zhengjy
 * @since 2021/05/07
 * Description:
 */
class GroupViewModel(
    val repository: GroupRepository,
    val connection: ConnectionManager,
    delegate: LoginDelegate,
) : LoadingViewModel(), LoginDelegate by delegate {

    private val database: ChatDatabase
        get() = ChatDatabaseProvider.provide()

    private val ossService by route<OssService>(OssModule.APP_OSS)

    private val _createResult = MutableLiveData<GroupInfoTO>()
    val createResult: LiveData<GroupInfoTO>
        get() = _createResult

    private val _groupPubResult = MutableLiveData<GroupInfoTO>()
    val groupPubResult: LiveData<GroupInfoTO>
        get() = _groupPubResult

    private val _groupJoinResult = MutableLiveData<Any>()
    val groupJoinResult: LiveData<Any>
        get() = _groupJoinResult

    private val _createApplyResult = MutableLiveData<Any>()
    val createApplyResult: LiveData<Any>
        get() = _createApplyResult

    private val _groupApplyListResult = MutableLiveData<List<GroupApplyTO>>()
    val groupApplyListResult: LiveData<List<GroupApplyTO>>
        get() = _groupApplyListResult

    private val _applyAcceptResult = MutableLiveData<Any>()
    val applyAcceptResult: LiveData<Any>
        get() = _applyAcceptResult

    private val _applyRejectResult = MutableLiveData<Any>()
    val applyRejectResult: LiveData<Any>
        get() = _applyRejectResult

    private val _editAvatarResult = MutableLiveData<String>()
    val editAvatarResult: LiveData<String>
        get() = _editAvatarResult

    private val _editNameResult = MutableLiveData<String>()
    val editNameResult: LiveData<String>
        get() = _editNameResult

    private val _changeRoleResult = MutableLiveData<Any>()
    val changeRoleResult: LiveData<Any>
        get() = _changeRoleResult

    private val _inviteResult = MutableLiveData<Any>()
    val inviteResult: LiveData<Any>
        get() = _inviteResult

    private val _removeResult = MutableLiveData<Any>()
    val removeResult: LiveData<Any>
        get() = _removeResult

    private val _exitResult = MutableLiveData<Any>()
    val exitResult: LiveData<Any>
        get() = _exitResult

    private val _disbandResult = MutableLiveData<Any>()
    val disbandResult: LiveData<Any>
        get() = _disbandResult


    fun createGroup(url: String, name: String, users: List<String>) {
        request<GroupInfoTO> {
            onRequest {
                repository.createGroup(url, name, users)
            }
            onSuccess {
                _createResult.value = it
            }
        }
    }

    fun editGroupAvatar(gid: Long, path: String) {
        request<Any> {
            onRequest {
                val url = uploadMedia(gid, path, MediaType.PICTURE)
                if (url.isEmpty()) {
                    Result.Error(ApiException("upload fail"))
                } else {
                    repository.editGroupAvatar(null, gid, url)
                }
            }
            onSuccess {
                _editAvatarResult.value = path
            }
        }
    }

    private suspend fun uploadMedia(gid: Long, path: String?, @MediaType type: Int): String {
        return try {
            val info = database.groupDao().getGroupInfo(gid) ?: return ""
            if (path.isContent()) {
                ossService?.uploadMedia(info.server.address, path?.toUri(), type) ?: ""
            } else {
                ossService?.uploadMedia(info.server.address, path, type) ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun editGroupName(gid: Long, name: String) {
        request<Any> {
            onRequest {
                repository.editGroupName(null, gid, name)
            }
            onSuccess {
                _editNameResult.value = name
            }
        }
    }

    fun editGroupNames(gid: Long, name: String, publicName: String) {
        request<Any> {
            onRequest {
                repository.editGroupNames(null, gid, name, publicName)
            }
            onSuccess {
                _editNameResult.value = name
            }
        }
    }

    fun editNicknameInGroup(gid: Long, name: String?) {
        request<Any> {
            onRequest {
                repository.editNicknameInGroup(null, gid, name ?: "")
            }
            onSuccess {
                _editNameResult.value = name ?: ""
            }
        }
    }

    fun inviteGroupMembers(url: String, gid: Long, users: List<String>) {
        request<Any> {
            onRequest {
                repository.inviteMembers(url, gid, users)
            }
            onSuccess {
                _inviteResult.value = it
            }
        }
    }

    /**
     * 判断当前群"邀请入群"是否需要审批：
     * 群加群限制为"群主审批"且自己不是群主/管理员时，邀请会转为入群申请
     */
    suspend fun isInviteNeedApprove(gid: Long): Boolean {
        val info = database.groupDao().getGroupInfo(gid) ?: return false
        if (info.joinType != GroupInfo.NEED_APPROVE_GROUP) return false
        val user = database.groupUserDao().getGroupUser(gid, getAddress() ?: "")
        return user == null || user.role == GroupUser.LEVEL_USER
    }

    fun removeGroupMembers(url: String, gid: Long, users: List<String>) {
        request<Any> {
            onRequest {
                repository.removeMembers(url, gid, users)
            }
            onSuccess {
                _removeResult.value = it
            }
        }
    }

    fun getGroupInfoLocal(gid: Long) = database.groupDao().getGroupInfoLive(gid)

    fun getGroupInfo(gid: Long) {
        request<GroupInfoTO>(false) {
            onRequest {
                repository.getGroupInfo(null, gid)
            }
        }
    }

    fun getGroupPubInfo(gid: Long, url: String?) {
        request<GroupInfoTO>(true) {
            onRequest {
                repository.getGroupPubInfo(url, gid)
            }

            onSuccess {
                _groupPubResult.value = it
            }
        }
    }

    fun joinGroup(gid: Long, url: String?, inviterId: String?) {
        request<Any>(true) {
            onRequest {
                repository.joinGroup(url, gid, inviterId)
            }

            onSuccess {
                _groupJoinResult.value = it
            }
        }
    }

    /**
     * 提交入群申请（joinType=2 时群外申请加入）
     */
    fun createGroupApply(gid: Long, url: String?, applyNote: String? = null) {
        request<Any>(true) {
            onRequest {
                repository.createGroupApply(url, gid, applyNote)
            }

            onSuccess {
                _createApplyResult.value = it
            }
        }
    }

    /**
     * 拉取入群申请列表（群主/管理员，分页 count/offset）
     */
    fun getGroupApplys(gid: Long, url: String? = null, count: Int = 50, offset: Int = 0) {
        request<GroupApplyTO.Wrapper>(true) {
            onRequest {
                repository.getGroupApplys(url, gid, count, offset)
            }

            onSuccess {
                _groupApplyListResult.value = it.data
            }
        }
    }

    /**
     * 同意入群申请（群主/管理员）
     */
    fun acceptGroupApply(gid: Long, applyId: String) {
        request<Any>(true) {
            onRequest {
                repository.acceptGroupApply(null, gid, applyId)
            }

            onSuccess {
                _applyAcceptResult.value = it
            }
        }
    }

    /**
     * 拒绝入群申请（群主/管理员）
     */
    fun rejectGroupApply(gid: Long, applyId: String, reason: String? = null) {
        request<Any>(true) {
            onRequest {
                repository.rejectGroupApply(null, gid, applyId, reason)
            }

            onSuccess {
                _applyRejectResult.value = it
            }
        }
    }


    fun getGroupList(url: String) {
        launch {
            repository.getGroupList(url)
        }
    }

    fun getGroupUserList(
        gid: Long,
        keywords: String?,
        refresh: Boolean
    ): LiveData<List<GroupUser>> = liveData(Dispatchers.Main) {
        if (keywords.isNullOrEmpty()) {
            emitSource(database.groupUserDao().getGroupUserList(gid))
        } else {
            emitSource(database.groupUserDao().getGroupUserListByKeywords(gid, keywords.toLikeKey()))
        }
        if (refresh) {
            repository.getGroupUserList(null, gid)
        }
    }

    fun changeOwner(gid: Long, address: String) {
        request<Any> {
            onRequest {
                repository.changeGroupOwner(null, gid, address)
            }
            onSuccess {
                _changeRoleResult.value = it
            }
        }
    }

    fun changeGroupUserRole(gid: Long, address: String, role: Int) {
        request<Any> {
            onRequest {
                repository.changeGroupUserRole(null, gid, address, role)
            }
            onSuccess {
                _changeRoleResult.value = it
            }
        }
    }

    fun getMuteGroupUserList(gid: Long): LiveData<List<GroupUser>> = liveData(Dispatchers.Main) {
        emitSource(
            database.groupUserDao().getGroupUserListByMuteTime(gid, System.currentTimeMillis())
                .map { it.filter { user -> user.muteTime > System.currentTimeMillis() } }
        )
    }

    fun getGroupAdmin(gid: Long) =
        database.groupUserDao().getGroupUserListByRole(gid, GroupUser.LEVEL_ADMIN)

    fun changeFriendType(gid: Long, friendType: Int) {
        request<Any> {
            onRequest {
                repository.changeFriendType(null, gid, friendType)
            }
        }
    }

    fun changeJoinType(gid: Long, joinType: Int) {
        request<Any> {
            onRequest {
                repository.changeJoinType(null, gid, joinType)
            }
        }
    }

    fun changeMuteType(gid: Long, muteAll: Boolean) {
        val muteType = if (muteAll) GroupInfo.MUTE_ALL else GroupInfo.NOT_MUTE_ALL
        request<Any> {
            onRequest {
                repository.changeMuteType(null, gid, muteType)
            }
        }
    }

    fun changeMuteTime(gid: Long, muteTime: Long, members: List<String>) {
        request<Any> {
            onRequest {
                repository.changeMuteTime(null, gid, muteTime, members)
            }
        }
    }

    fun changeStickTop(gid: Long, stickTop: Boolean) {
        launch {
            if (stickTop) {
                database.groupDao().addFlag(gid, Contact.STICK_TOP)
            } else {
                database.groupDao().deleteFlag(gid, Contact.STICK_TOP)
            }
        }
    }

    fun changeNoDisturb(gid: Long, noDisturb: Boolean) {
        launch {
            if (noDisturb) {
                database.groupDao().addFlag(gid, Contact.NO_DISTURB)
            } else {
                database.groupDao().deleteFlag(gid, Contact.NO_DISTURB)
            }
        }
    }

    fun exitGroup(url: String?, gid: Long) {
        request<Any> {
            onRequest {
                repository.exitGroup(url, gid)
            }
            onSuccess {
                _exitResult.value = it
            }
        }
    }

    fun disbandGroup(url: String?, gid: Long) {
        request<Any> {
            onRequest {
                repository.disbandGroup(url, gid)
            }
            onSuccess {
                _disbandResult.value = it
            }
        }
    }
}