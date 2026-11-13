package com.fzm.chat.vm

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.fzm.chat.core.data.ChatConst
import com.fzm.chat.core.data.bean.Field
import com.fzm.chat.core.session.LoginDelegate
import com.zjy.architecture.mvvm.LoadingViewModel
import com.zjy.architecture.mvvm.request

/**
 * @author zhengjy
 * @since 2020/12/16
 * Description:
 */
class EditInfoViewModel(
    delegate: LoginDelegate
) : LoadingViewModel(), LoginDelegate by delegate {

    private val _nicknameResult by lazy { MutableLiveData<String>() }
    val nicknameResult: LiveData<String>
        get() = _nicknameResult

    fun setNickname(newNickname: String) {
        request<String> {
            onRequest {
                setUserInfo(listOf(Field(ChatConst.UserField.NICKNAME, newNickname, 1)))
            }
            onSuccess {
                // 链上交易成功后必须同步本地缓存，否则「我的」页继续显示旧昵称，
                // 只有重新登录（走 getUserInfo 拉链上资料）才会更新。
                updateInfo { nickname = newNickname }
                _nicknameResult.value = it
            }
        }
    }
}