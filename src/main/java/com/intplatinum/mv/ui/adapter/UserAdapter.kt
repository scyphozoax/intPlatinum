package com.intplatinum.mv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.intplatinum.mv.data.UserInfo
import com.intplatinum.mv.databinding.ItemUserBinding

class UserAdapter(
    private val onUserClick: (UserInfo) -> Unit = {}
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    private val users = mutableListOf<UserInfo>()

    fun updateUsers(newUsers: List<UserInfo>) {
        users.clear()
        users.addAll(newUsers)
        notifyDataSetChanged()
    }

    fun addUser(user: UserInfo) {
        if (!users.any { it.username == user.username }) {
            users.add(user)
            notifyItemInserted(users.size - 1)
        }
    }

    fun removeUser(username: String) {
        val index = users.indexOfFirst { it.username == username }
        if (index != -1) {
            users.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun getUserCount(): Int = users.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(users[position])
    }

    override fun getItemCount(): Int = users.size

    inner class UserViewHolder(private val binding: ItemUserBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(user: UserInfo) {
            binding.tvUsername.text = user.username
            binding.tvUserStatus.text = "在线"
            
            // 设置在线状态指示器
            binding.viewOnlineIndicator.alpha = 1.0f
            
            // 设置点击事件
            binding.root.setOnClickListener {
                onUserClick(user)
            }
        }
    }
}