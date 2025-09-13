package com.intplatinum.mv.data

import com.google.gson.annotations.SerializedName

/**
 * 消息数据类
 */
data class Message(
    @SerializedName("type")
    val type: String,
    
    @SerializedName("sender")
    val sender: String? = null,
    
    @SerializedName("content")
    val content: String? = null,
    
    @SerializedName("timestamp")
    val timestamp: Long? = null,
    
    @SerializedName("file_type")
    val fileType: String? = null,
    
    @SerializedName("file_name")
    val fileName: String? = null,
    
    @SerializedName("original_file_name")
    val originalFileName: String? = null,
    
    @SerializedName("file_data")
    val fileData: String? = null,
    
    @SerializedName("users")
    val users: List<UserInfo>? = null,
    
    @SerializedName("username")
    val username: String? = null,
    
    @SerializedName("version")
    val version: String? = null,
    
    @SerializedName("required_version")
    val requiredVersion: String? = null
) {
    companion object {
        // 消息类型常量
        const val TYPE_TEXT = "text"
        const val TYPE_FILE = "file"
        const val TYPE_SYSTEM = "system"
        const val TYPE_USER_LIST = "user_list"
        const val TYPE_CONNECTED = "connected"
        const val TYPE_ERROR = "error"
        const val TYPE_VERSION_ACCEPTED = "version_accepted"
        const val TYPE_VERSION_MISMATCH = "version_mismatch"
        
        // 文件类型常量
        const val FILE_TYPE_IMAGES = "images"
        const val FILE_TYPE_TEXT = "text"
    }
}

/**
 * 用户信息数据类
 */
data class UserInfo(
    @SerializedName("username")
    val username: String,
    
    @SerializedName("ip")
    val ip: String? = null
)

/**
 * 聊天消息显示数据类
 */
data class ChatMessage(
    val id: String = System.currentTimeMillis().toString(),
    val type: MessageType,
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean = false,
    val fileName: String? = null,
    val filePath: String? = null
)

/**
 * 消息类型枚举
 */
enum class MessageType {
    TEXT,
    IMAGE,
    SYSTEM
}