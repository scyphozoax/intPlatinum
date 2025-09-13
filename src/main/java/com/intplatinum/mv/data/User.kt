package com.intplatinum.mv.data

import com.google.gson.annotations.SerializedName

/**
 * 用户数据类
 */
data class User(
    @SerializedName("username")
    val username: String,
    
    @SerializedName("ip")
    val ip: String? = null,
    
    @SerializedName("is_online")
    val isOnline: Boolean = true,
    
    @SerializedName("last_seen")
    val lastSeen: Long? = null
) {
    /**
     * 获取显示名称
     */
    fun getDisplayName(): String {
        return username
    }
    
    /**
     * 获取状态文本
     */
    fun getStatusText(): String {
        return if (isOnline) "在线" else "离线"
    }
    
    /**
     * 获取IP地址显示文本
     */
    fun getIpDisplayText(): String {
        return ip ?: "未知"
    }
}

/**
 * 用户状态枚举
 */
enum class UserStatus {
    ONLINE,
    OFFLINE,
    AWAY
}