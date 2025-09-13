package com.intplatinum.mv

import android.app.Application
import com.intplatinum.mv.network.ChatClientManager

class ChatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // 应用终止时清理ChatClient连接
        ChatClientManager.clearChatClient()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        // 内存不足时也可以考虑清理连接
        // ChatClientManager.clearChatClient()
    }
}