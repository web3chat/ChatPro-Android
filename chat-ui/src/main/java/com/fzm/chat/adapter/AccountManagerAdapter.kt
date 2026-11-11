package com.fzm.chat.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fzm.chat.R
import com.fzm.chat.databinding.ItemAccountManagerBinding
import com.fzm.wallet.sdk.db.entity.PWallet
import com.zjy.architecture.ext.load

/**
 * 账户管理列表适配器，每个item根据钱包的用户地址从本地缓存读取对应的头像和昵称
 * @param profileMap  用户地址 -> Pair(avatar, nickname) 的映射，从 MMKV 缓存读取
 * @param addressMap  钱包ID -> Coin.address 的映射，展示每个钱包对应的链上地址
 * @param chatAddrMap 钱包ID -> 用户聊天地址 的映射，用于查找 profileMap
 */
class AccountManagerAdapter(
    val context: Context,
    private val profileMap: Map<String, Pair<String, String>>,
    private val addressMap: Map<Long, String>,
    private val chatAddrMap: Map<Long, String>,
    private val currentWalletId: Long = -1L
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var list: List<PWallet> = mutableListOf()

    fun setData(list: List<PWallet>) {
        this.list = list
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemAccountManagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    /**
     * 绑定item数据：
     * - iv_avatar: 从缓存加载该账户的头像
     * - tv_name: 从缓存加载该账户的昵称
     * - tv_account: 显示该钱包对应Coin的链上地址
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ViewHolder -> {
                val item = list[position]
                val chatAddr = chatAddrMap[item.id].orEmpty()
                val profile = profileMap[chatAddr]
                val avatar = profile?.first.orEmpty()
                val nickname = profile?.second.orEmpty()

                holder.binding.ivAvatar.load(avatar, R.mipmap.default_avatar_round)
                holder.binding.tvName.text = nickname.takeIf { it.isNotBlank() }
                    ?: item.name?.takeIf { it.isNotBlank() }
                    ?: holder.itemView.context.getString(R.string.chat_no_nickname)
                holder.binding.tvAccount.text = addressMap[item.id].orEmpty()

                val isCurrent = item.id == currentWalletId
                val container = (holder.itemView as ViewGroup).getChildAt(0)
                container.setBackgroundResource(
                    if (isCurrent) R.drawable.bg_account_item_current else R.drawable.bg_account_item
                )
                holder.binding.tvCurrent.visibility =
                    if (isCurrent) View.VISIBLE else View.GONE

                holder.itemView.setOnClickListener { clickListener(position) }
            }
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }

    lateinit var clickListener: (Int) -> Unit

    fun setOnItemClickListener(listener: (Int) -> Unit) {
        this.clickListener = listener
    }

    inner class ViewHolder(val binding: ItemAccountManagerBinding) :
        RecyclerView.ViewHolder(binding.root)

}
