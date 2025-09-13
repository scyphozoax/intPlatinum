package com.intplatinum.mv.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 连接设置管理工具类
 */
class ConnectionPreferences(context: Context) {
    
    companion object {
        private const val PREF_NAME = "connection_settings"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_REMEMBER_SETTINGS = "remember_settings"
        private const val KEY_LAST_CONNECTION_SUCCESS = "last_connection_success"
        
        private const val DEFAULT_SERVER_IP = "192.168.1.100"
        private const val DEFAULT_SERVER_PORT = 12345
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    /**
     * 保存连接设置
     */
    fun saveConnectionSettings(
        serverIp: String,
        serverPort: Int,
        username: String,
        rememberSettings: Boolean = true
    ) {
        sharedPreferences.edit().apply {
            putString(KEY_SERVER_IP, serverIp)
            putInt(KEY_SERVER_PORT, serverPort)
            putString(KEY_USERNAME, username)
            putBoolean(KEY_REMEMBER_SETTINGS, rememberSettings)
            apply()
        }
    }
    
    /**
     * 标记最后一次连接成功
     */
    fun markLastConnectionSuccess() {
        sharedPreferences.edit().apply {
            putBoolean(KEY_LAST_CONNECTION_SUCCESS, true)
            apply()
        }
    }
    
    /**
     * 清除连接成功标记
     */
    fun clearLastConnectionSuccess() {
        sharedPreferences.edit().apply {
            putBoolean(KEY_LAST_CONNECTION_SUCCESS, false)
            apply()
        }
    }
    
    /**
     * 获取保存的服务器IP
     */
    fun getServerIp(): String {
        return sharedPreferences.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
    }
    
    /**
     * 获取保存的服务器端口
     */
    fun getServerPort(): Int {
        return sharedPreferences.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT)
    }
    
    /**
     * 获取保存的用户名
     */
    fun getUsername(): String {
        return sharedPreferences.getString(KEY_USERNAME, "") ?: ""
    }
    
    /**
     * 是否记住设置
     */
    fun isRememberSettings(): Boolean {
        return sharedPreferences.getBoolean(KEY_REMEMBER_SETTINGS, false)
    }
    
    /**
     * 上次连接是否成功
     */
    fun wasLastConnectionSuccessful(): Boolean {
        return sharedPreferences.getBoolean(KEY_LAST_CONNECTION_SUCCESS, false)
    }
    
    /**
     * 获取完整的连接设置
     */
    fun getConnectionSettings(): ConnectionSettings {
        return ConnectionSettings(
            serverIp = getServerIp(),
            serverPort = getServerPort(),
            username = getUsername(),
            rememberSettings = isRememberSettings(),
            lastConnectionSuccess = wasLastConnectionSuccessful()
        )
    }
    
    /**
     * 清除所有保存的设置
     */
    fun clearAllSettings() {
        sharedPreferences.edit().clear().apply()
    }
    
    /**
     * 连接设置数据类
     */
    data class ConnectionSettings(
        val serverIp: String,
        val serverPort: Int,
        val username: String,
        val rememberSettings: Boolean,
        val lastConnectionSuccess: Boolean
    )
}