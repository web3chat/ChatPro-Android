package com.fzm.chat.login.words

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fzm.chat.databinding.ItemWordsBinding

class WordsAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var list: List<String> = mutableListOf()

    fun setData(list: MutableList<String>) {
        this.list = list
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemWordsBinding.inflate(LayoutInflater.from(parent.context))
        return ViewHolder(binding)
    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ViewHolder -> {
                val item = list[position]
                holder.binding.tvWords.text = item
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

    inner class ViewHolder(val binding: ItemWordsBinding) :
        RecyclerView.ViewHolder(binding.root)


}