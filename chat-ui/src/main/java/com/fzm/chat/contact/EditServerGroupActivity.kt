package com.fzm.chat.contact

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.core.widget.addTextChangedListener
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.fzm.chat.R
import com.fzm.chat.biz.base.BizActivity
import com.fzm.chat.core.data.bean.ServerGroupInfo
import com.fzm.chat.core.data.bean.ServerNode
import com.fzm.chat.databinding.ActivityEditServerGroupBinding
import com.fzm.chat.databinding.ItemServerHistoryBinding
import com.fzm.chat.router.main.MainModule
import com.fzm.chat.vm.ServerManageViewModel
import com.fzm.chat.widget.ListPopup
import com.fzm.widget.dialog.EasyDialog
import com.zjy.architecture.base.instance
import com.zjy.architecture.ext.dp
import com.zjy.architecture.ext.toast
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author zhengjy
 * @since 2021/02/22
 * Description:服务器分组创建编辑界面
 */
@Route(path = MainModule.EDIT_SERVER_GROUP)
class EditServerGroupActivity : BizActivity() {

    /**
     * 分组信息，如果是创建则为空
     */
    @JvmField
    @Autowired
    var info: ServerGroupInfo? = null

    /**
     * 是否是默认分组
     */
    @JvmField
    @Autowired
    var defaultGroup: Boolean = false

    private val viewModel by viewModel<ServerManageViewModel>()
    private val binding by init { ActivityEditServerGroupBinding.inflate(layoutInflater) }

    override val root: View
        get() = binding.root

    override fun initView() {
        ARouter.getInstance().inject(this)
        if (info != null) {
            binding.ctbTitle.setTitle(getString(R.string.chat_login_edit_chat_server_group))
            binding.tvNameCount.text = getString(R.string.chat_tips_num_15, info!!.name.length)
            binding.etName.setText(info!!.name)
            binding.etName.setSelection(info!!.name.length)
            binding.etAddress.setText(info!!.value)
            binding.etAddress.setSelection(info!!.value.length)
            binding.ctbTitle.setRightVisible(true)
        } else {
            binding.ctbTitle.setTitle(getString(R.string.chat_login_add_chat_server_group))
            binding.ctbTitle.setRightVisible(false)
        }
    }

    override fun initData() {
        viewModel.loading.observe(this) { setupLoading(it) }
        viewModel.addResult.observe(this) {
            toast(R.string.chat_tip_add_server_group_success)
            dismiss()
            setResult(RESULT_OK)
            finish()
        }
        viewModel.delResult.observe(this) {
            toast(R.string.chat_tip_del_server_group_success)
            dismiss()
            setResult(RESULT_OK)
            finish()
        }
        viewModel.editResult.observe(this) {
            toast(R.string.chat_tip_edit_server_group_success)
            dismiss()
            setResult(RESULT_OK)
            finish()
        }
        viewModel.chatList.observe(this) {
            adapter.setData(it)
            if (pendingHistoryPopup && it.isNotEmpty()) {
                pendingHistoryPopup = false
                showHistoryPopup()
            }
        }
    }

    override fun setEvent() {
        binding.ctbTitle.setOnLeftClickListener { finish() }
        binding.ctbTitle.setOnRightClickListener {
            info?.apply {
                if (defaultGroup) {
                    toast(R.string.chat_tip_cant_del_default_server)
                    return@apply
                }
                EasyDialog.Builder()
                    .setHeaderTitle(getString(R.string.biz_tips))
                    .setContent(getString(R.string.chat_tip_confirm_to_del_server_group))
                    .setBottomLeftText(getString(R.string.biz_cancel))
                    .setBottomRightText(getString(R.string.biz_confirm))
                    .setBottomRightClickListener { dialog ->
                        viewModel.deleteServerGroup(id)
                        dialog.dismiss()
                    }
                    .create(instance)
                    .show()
            }
        }
        binding.etName.addTextChangedListener {
            if (it.isNullOrEmpty()) {
                binding.tvNameCount.text = getString(R.string.chat_tips_num_15, 0)
            } else {
                binding.tvNameCount.text = getString(R.string.chat_tips_num_15, it.length)
            }
        }
        binding.etAddress.addTextChangedListener {
            // 手动输入/粘贴地址时，如果和历史服务器完全匹配，名称同步改成对应名称
            val address = it?.toString()?.trim().orEmpty()
            if (address.isNotEmpty()) {
                adapter.find(address)?.let { node -> applyServerNode(node, updateAddress = false) }
            }
        }
        binding.ivHistory.setOnClickListener {
            if (adapter.count > 0) {
                showHistoryPopup()
            } else {
                // 首次点击时列表还没拉到，等数据回来再弹
                pendingHistoryPopup = true
                viewModel.fetchServerList()
            }
        }
        binding.tvSubmit.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val address = binding.etAddress.text.toString().trim()
            try {
                val uri = address.toHttpUrl()
                if (uri.scheme != "https" && uri.scheme != "http") {
                    toast(R.string.chat_tip_wrong_server_protocol)
                    return@setOnClickListener
                }
                if (uri.port > 65535 || uri.port < 1) {
                    toast(R.string.chat_tip_wrong_server_port)
                    return@setOnClickListener
                }
                if (info != null) {
                    viewModel.editServerGroup(info!!.id, name, address)
                } else {
                    viewModel.addServerGroup(name, address)
                }
            } catch (e: Exception) {
                toast(R.string.chat_tip_wrong_server_format)
            }
        }
    }

    private val adapter by lazy { HistoryAdapter(this) }

    /** 首次点击历史按钮时，等服务器列表回来再弹下拉 */
    private var pendingHistoryPopup = false

    /**
     * 弹出服务器历史下拉；选中后同时回填地址和名称。
     *
     * 名称只在输入框为空时自动填，避免覆盖用户已经输入的自定义名称。
     */
    private fun showHistoryPopup() {
        val popup = ListPopup(this, adapter)
        popup.create(binding.slAddress.measuredWidth, 200.dp) { _, _, position, _ ->
            val node = adapter.getItem(position) as ServerNode.Node
            applyServerNode(node)
            popup.dismiss()
        }
        popup.setPositionOffsetYWhenBottom((-10).dp)
        popup.show(binding.slAddress)
    }

    /**
     * 把服务器节点回填到表单。
     *
     * 切换服务器时名称要和地址一起变；只在用户手动输入地址命中历史记录时
     * 才跳过地址回填（避免打断输入）。
     */
    private fun applyServerNode(node: ServerNode.Node, updateAddress: Boolean = true) {
        if (updateAddress && binding.etAddress.text.toString() != node.address) {
            binding.etAddress.setText(node.address)
            binding.etAddress.setSelection(node.address.length)
        }
        if (node.name.isNotBlank() && binding.etName.text.toString() != node.name) {
            binding.etName.setText(node.name)
            binding.etName.setSelection(node.name.length)
        }
    }

    private class HistoryAdapter(private val context: Context) : BaseAdapter() {

        /** 按地址精确匹配（手动输入地址时同步名称用） */
        fun find(address: String): ServerNode.Node? =
            data.firstOrNull { it.address.equals(address, ignoreCase = true) }

        fun setData(list: List<ServerNode.Node>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        private val data = mutableListOf<ServerNode.Node>()

        override fun getCount(): Int {
            return data.size
        }

        override fun getItem(position: Int): Any {
            return data[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val root: View
            val bind: ItemServerHistoryBinding
            if (convertView == null) {
                bind = ItemServerHistoryBinding.inflate(LayoutInflater.from(context))
                root = bind.root
                root.tag = bind
            } else {
                root = convertView
                bind = root.tag as ItemServerHistoryBinding
            }
            bind.tvName.text = data[position].name
            bind.tvAddress.text = data[position].address

            return root
        }
    }
}