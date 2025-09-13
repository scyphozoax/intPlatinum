package com.intplatinum.mv.network

/**
 * ChatClient管理器，使用单例模式管理ChatClient实例
 */
object ChatClientManager {
    private var chatClient: ChatClient? = null
    
    fun setChatClient(client: ChatClient) {
        chatClient = client
    }
    
    fun getChatClient(): ChatClient? {
        return chatClient
    }
    
    fun clearChatClient() {
        chatClient?.cleanup()
        chatClient = null
    }
}