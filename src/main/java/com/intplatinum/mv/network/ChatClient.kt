package com.intplatinum.mv.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.intplatinum.mv.data.Message
import com.intplatinum.mv.data.ChatMessage
import com.intplatinum.mv.data.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 聊天客户端网络通信类
 */
class ChatClient(private val context: Context) {
    companion object {
        private const val TAG = "ChatClient"
        private const val CLIENT_VERSION = "1.0.0-mv"
        private const val BUFFER_SIZE = 8192
        private const val CONNECTION_TIMEOUT = 10000 // 10秒连接超时
        private const val READ_TIMEOUT = 60000 // 60秒读取超时，避免因网络延迟导致断开
        private const val HEARTBEAT_INTERVAL = 30000L // 30秒心跳间隔
        private const val MAX_RECONNECT_ATTEMPTS = 3 // 最大重连次数
        private const val RECONNECT_DELAY = 5000L // 重连延迟5秒
    }
    
    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var isConnected = false
    private val gson = Gson()
    private var heartbeatJob: Job? = null
    private var lastHeartbeatTime = 0L
    private var reconnectAttempts = 0
    private var isReconnecting = false
    private var lastConnectionParams: Triple<String, Int, String>? = null
    
    // 协程作用域
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 消息流
    private val _messageFlow = MutableSharedFlow<ChatMessage>()
    val messageFlow: SharedFlow<ChatMessage> = _messageFlow.asSharedFlow()
    
    // 回调接口
    interface ChatClientListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(message: Message)
        fun onError(error: String)
        fun onVersionMismatch(requiredVersion: String)
    }
    
    private var listener: ChatClientListener? = null
    
    fun setListener(listener: ChatClientListener) {
        this.listener = listener
    }
    
    /**
     * 连接到服务器
     */
    suspend fun connect(host: String, port: Int, username: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "正在连接服务器: $host:$port")
                
                // 保存连接参数用于重连
                lastConnectionParams = Triple(host, port, username)
                
                // 创建Socket连接，设置超时
                socket = Socket()
                Log.d(TAG, "Socket创建成功，开始连接...")
                socket!!.connect(InetSocketAddress(host, port), CONNECTION_TIMEOUT)
                socket!!.soTimeout = READ_TIMEOUT
                
                inputStream = DataInputStream(socket!!.getInputStream())
                outputStream = DataOutputStream(socket!!.getOutputStream())
                
                Log.d(TAG, "Socket连接建立成功，输入输出流初始化完成")
                
                // 发送版本信息
                Log.d(TAG, "开始版本验证流程...")
                if (!sendVersionInfo()) {
                    Log.e(TAG, "版本验证失败，断开连接")
                    disconnect()
                    return@withContext false
                }
                Log.d(TAG, "版本验证成功")
                
                // 发送用户名
                Log.d(TAG, "开始用户名验证流程...")
                if (!sendUsername(username)) {
                    Log.e(TAG, "用户名验证失败，断开连接")
                    disconnect()
                    return@withContext false
                }
                Log.d(TAG, "用户名验证成功")
                
                isConnected = true
                reconnectAttempts = 0 // 重置重连计数
                
                // 启动消息接收协程
                Log.d(TAG, "启动消息接收循环")
                startReceiving()
                
                // 启动心跳包机制
                Log.d(TAG, "启动心跳包机制")
                startHeartbeat()
                
                withContext(Dispatchers.Main) {
                    listener?.onConnected()
                }
                
                Log.d(TAG, "连接建立完成")
                true
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "连接超时", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("连接超时，请检查网络连接和服务器状态")
                }
                false
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "连接被拒绝", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("无法连接到服务器，请检查服务器地址和端口")
                }
                false
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "主机名解析失败", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("无法解析服务器地址，请检查网络连接")
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "连接失败", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("连接服务器失败: ${e.javaClass.simpleName} - ${e.message}")
                }
                false
            }
        }
    }
    
    /**
     * 发送版本信息
     */
    private suspend fun sendVersionInfo(): Boolean {
        return try {
            Log.d(TAG, "开始发送版本信息...")
            
            val encryptedVersion = Base64.encodeToString(CLIENT_VERSION.toByteArray(), Base64.DEFAULT).trim()
            val versionMessage = Message(
                type = "version",
                version = encryptedVersion
            )
            
            val jsonData = gson.toJson(versionMessage)
            val messageBytes = jsonData.toByteArray(Charsets.UTF_8)
            
            Log.d(TAG, "准备发送版本信息，消息长度: ${messageBytes.size}")
            
            // 发送消息长度（4字节）
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(messageBytes.size).array()
            outputStream?.write(lengthBytes)
            outputStream?.flush()
            Log.d(TAG, "已发送消息长度: ${messageBytes.size}")
            
            // 发送消息内容
            outputStream?.write(messageBytes)
            outputStream?.flush()
            Log.d(TAG, "已发送版本信息内容: $jsonData")
            
            // 等待服务器版本验证响应
            Log.d(TAG, "等待服务器版本验证响应...")
            val response = receiveMessage()
            Log.d(TAG, "收到服务器响应: ${response?.type}")
            
            when (response?.type) {
                Message.TYPE_VERSION_ACCEPTED -> {
                    Log.d(TAG, "版本验证通过")
                    true
                }
                Message.TYPE_VERSION_MISMATCH -> {
                    Log.e(TAG, "版本不匹配，需要版本: ${response.requiredVersion}")
                    withContext(Dispatchers.Main) {
                        listener?.onVersionMismatch(response.requiredVersion ?: "未知版本")
                    }
                    false
                }
                else -> {
                    Log.e(TAG, "版本验证失败，响应类型: ${response?.type}")
                    withContext(Dispatchers.Main) {
                        listener?.onError("版本验证失败")
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送版本信息失败", e)
            false
        }
    }
    
    /**
     * 发送用户名
     */
    private suspend fun sendUsername(username: String): Boolean {
        return try {
            val usernameMessage = Message(
                type = "username",
                username = username
            )
            
            val jsonData = gson.toJson(usernameMessage)
            val messageBytes = jsonData.toByteArray(Charsets.UTF_8)
            
            // 发送消息长度（4字节）
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(messageBytes.size).array()
            outputStream?.write(lengthBytes)
            
            // 发送消息内容
            outputStream?.write(messageBytes)
            outputStream?.flush()
            
            Log.d(TAG, "已发送用户名信息: $jsonData")
            
            // 等待服务器响应
            val response = receiveMessage()
            when (response?.type) {
                Message.TYPE_CONNECTED -> {
                    Log.d(TAG, "用户名验证通过")
                    true
                }
                Message.TYPE_ERROR -> {
                    Log.e(TAG, "用户名验证失败: ${response.content}")
                    withContext(Dispatchers.Main) {
                        listener?.onError(response.content ?: "用户名验证失败")
                    }
                    false
                }
                else -> {
                    Log.e(TAG, "用户名验证失败: $response")
                    withContext(Dispatchers.Main) {
                        listener?.onError("用户名验证失败")
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送用户名失败", e)
            false
        }
    }
    
    /**
     * 开始接收消息
     */
    private fun startReceiving() {
        clientScope.launch {
            Log.d(TAG, "开始消息接收循环...")
            try {
                var consecutiveNullCount = 0
                val maxConsecutiveNulls = 3 // 连续3次接收到null才认为连接有问题
                
                while (isConnected && socket?.isConnected == true) {
                    val message = receiveMessage()
                    if (message != null) {
                        consecutiveNullCount = 0 // 重置计数器
                        Log.d(TAG, "收到消息: ${message.type}")
                        
                        // 处理心跳包响应
                        if (message.type == "heartbeat" || message.type == "pong") {
                            Log.v(TAG, "收到心跳包响应")
                            continue // 心跳包不需要传递给UI层
                        }
                        
                        // 转换为ChatMessage并发送到Flow
                        val chatMessage = convertToChatMessage(message)
                        if (chatMessage != null) {
                            _messageFlow.emit(chatMessage)
                        }
                        
                        withContext(Dispatchers.Main) {
                            listener?.onMessageReceived(message)
                        }
                    } else {
                        consecutiveNullCount++
                        Log.d(TAG, "接收到null消息，连续次数: $consecutiveNullCount")
                        
                        // 如果连续多次接收到null，可能连接有问题
                        if (consecutiveNullCount >= maxConsecutiveNulls) {
                            Log.w(TAG, "连续接收到null消息，检查连接状态")
                            if (!socket?.isConnected!!) {
                                Log.w(TAG, "Socket已断开，退出接收循环")
                                break
                            }
                        }
                        
                        // 短暂延迟，避免过于频繁的轮询
                        delay(100)
                    }
                }
                Log.d(TAG, "消息接收循环结束")
            } catch (e: Exception) {
                Log.e(TAG, "接收消息时发生错误", e)
                if (isConnected) {
                    withContext(Dispatchers.Main) {
                        listener?.onError("连接已断开: ${e.message}")
                    }
                }
            } finally {
                Log.d(TAG, "清理连接资源")
                disconnect(true) // 允许重连的断开
                
                // 尝试自动重连
                if (!isReconnecting) {
                    attemptReconnect()
                }
            }
        }
    }
    
    /**
     * 尝试自动重连
     */
    private fun attemptReconnect() {
        if (isReconnecting || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.d(TAG, "跳过重连：isReconnecting=$isReconnecting, attempts=$reconnectAttempts")
            return
        }
        
        val connectionParams = lastConnectionParams
        if (connectionParams == null) {
            Log.d(TAG, "没有保存的连接参数，跳过重连")
            return
        }
        
        isReconnecting = true
        reconnectAttempts++
        
        clientScope.launch {
            try {
                Log.d(TAG, "开始第 $reconnectAttempts 次重连尝试...")
                delay(RECONNECT_DELAY)
                
                val (host, port, username) = connectionParams
                val success = connect(host, port, username)
                
                if (success) {
                    Log.d(TAG, "重连成功")
                } else {
                    Log.d(TAG, "重连失败，尝试次数: $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        // 继续尝试重连
                        isReconnecting = false
                        attemptReconnect()
                    } else {
                        Log.d(TAG, "达到最大重连次数，停止重连")
                        withContext(Dispatchers.Main) {
                            listener?.onError("连接断开，重连失败")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "重连过程中发生异常", e)
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    isReconnecting = false
                    attemptReconnect()
                } else {
                    withContext(Dispatchers.Main) {
                        listener?.onError("连接断开，重连失败: ${e.message}")
                    }
                }
            } finally {
                isReconnecting = false
            }
        }
    }
    
    /**
     * 接收消息
     */
    private suspend fun receiveMessage(): Message? {
        return try {
            val inputStream = this.inputStream ?: return null
            
            // 读取消息长度（4字节）
            val lengthBytes = ByteArray(4)
            var totalRead = 0
            while (totalRead < 4) {
                val bytesRead = inputStream.read(lengthBytes, totalRead, 4 - totalRead)
                if (bytesRead == -1) {
                    Log.w(TAG, "读取消息长度时连接已关闭")
                    return null
                }
                totalRead += bytesRead
            }
            
            val messageLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int
            Log.d(TAG, "准备读取消息，长度: $messageLength")
            
            if (messageLength <= 0 || messageLength > 1024 * 1024) { // 限制消息大小为1MB
                Log.e(TAG, "无效的消息长度: $messageLength")
                return null
            }
            
            // 读取消息内容
            val messageBytes = ByteArray(messageLength)
            totalRead = 0
            while (totalRead < messageLength) {
                val bytesRead = inputStream.read(messageBytes, totalRead, messageLength - totalRead)
                if (bytesRead == -1) {
                    Log.w(TAG, "读取消息内容时连接已关闭，已读取: $totalRead/$messageLength")
                    return null
                }
                totalRead += bytesRead
            }
            
            val jsonData = String(messageBytes, Charsets.UTF_8)
            Log.d(TAG, "成功接收完整消息: $jsonData")
            
            gson.fromJson(jsonData, Message::class.java)
        } catch (e: SocketTimeoutException) {
            // 检查是否超过心跳包超时时间
            val currentTime = System.currentTimeMillis()
            if (lastHeartbeatTime > 0 && currentTime - lastHeartbeatTime > HEARTBEAT_INTERVAL * 3) {
                Log.w(TAG, "心跳包超时，可能连接已断开")
                return null
            }
            // 正常的读取超时，继续等待
            Log.v(TAG, "Socket读取超时，继续等待...")
            null
        } catch (e: java.net.SocketException) {
            if (isConnected) {
                Log.e(TAG, "Socket连接异常: ${e.message}")
            } else {
                Log.d(TAG, "连接已主动断开")
            }
            null
        } catch (e: EOFException) {
            Log.w(TAG, "连接已被服务器关闭")
            null
        } catch (e: Exception) {
            if (isConnected) {
                Log.e(TAG, "接收消息失败: ${e.javaClass.simpleName} - ${e.message}", e)
            } else {
                Log.d(TAG, "连接已断开，停止接收消息")
            }
            null
        }
    }
    
    /**
     * 启动心跳包机制
     */
    private fun startHeartbeat() {
        heartbeatJob = clientScope.launch {
            while (isConnected && socket?.isConnected == true) {
                try {
                    delay(HEARTBEAT_INTERVAL)
                    if (isConnected) {
                        sendHeartbeat()
                    }
                } catch (e: CancellationException) {
                    // 协程被取消是正常的断开连接流程，不需要记录错误
                    Log.d(TAG, "心跳包协程已取消")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "心跳包发送失败", e)
                    break
                }
            }
        }
    }
    
    /**
     * 发送心跳包
     */
    private suspend fun sendHeartbeat() {
        try {
            val heartbeatMessage = Message(
                type = "heartbeat",
                content = "ping",
                timestamp = System.currentTimeMillis()
            )
            
            if (sendMessage(heartbeatMessage)) {
                lastHeartbeatTime = System.currentTimeMillis()
                Log.v(TAG, "心跳包发送成功")
            } else {
                Log.w(TAG, "心跳包发送失败")
            }
        } catch (e: CancellationException) {
            // 协程被取消是正常的断开连接流程，重新抛出以正确处理
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "发送心跳包异常", e)
        }
    }
    
    /**
     * 将Message转换为ChatMessage
     */
    private fun convertToChatMessage(message: Message): ChatMessage? {
        return when (message.type) {
            Message.TYPE_TEXT -> {
                ChatMessage(
                    type = MessageType.TEXT,
                    sender = message.sender ?: "Unknown",
                    content = message.content ?: "",
                    timestamp = message.timestamp ?: System.currentTimeMillis()
                )
            }
            Message.TYPE_FILE -> {
                // 保存接收到的图片文件到本地
                val localFilePath = saveReceivedImageFile(message)
                ChatMessage(
                    type = MessageType.IMAGE,
                    sender = message.sender ?: "Unknown",
                    content = "[图片]",
                    timestamp = message.timestamp ?: System.currentTimeMillis(),
                    fileName = message.originalFileName,
                    filePath = localFilePath
                )
            }
            Message.TYPE_SYSTEM -> {
                ChatMessage(
                    type = MessageType.SYSTEM,
                    sender = "System",
                    content = message.content ?: "",
                    timestamp = message.timestamp ?: System.currentTimeMillis()
                )
            }
            else -> null
        }
    }
    
    /**
     * 保存接收到的图片文件到本地缓存目录
     */
    private fun saveReceivedImageFile(message: Message): String? {
        return try {
            val fileData = message.fileData
            val fileName = message.fileName ?: message.originalFileName ?: "image_${System.currentTimeMillis()}.jpg"
            
            if (fileData != null) {
                // 解码base64数据
                val imageBytes = Base64.decode(fileData, Base64.DEFAULT)
                
                // 创建缓存目录
                val cacheDir = File(context.cacheDir, "images")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                
                // 保存文件
                val imageFile = File(cacheDir, fileName)
                imageFile.writeBytes(imageBytes)
                
                Log.d(TAG, "图片已保存到: ${imageFile.absolutePath}")
                return imageFile.absolutePath
            } else {
                Log.e(TAG, "图片数据为空")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存图片文件失败", e)
            null
        }
    }
    
    /**
     * 发送文本消息
     */
    suspend fun sendTextMessage(content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected) {
                    return@withContext false
                }
                
                val message = Message(
                    type = Message.TYPE_TEXT,
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
                
                sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "发送文本消息失败", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("发送消息失败: ${e.message}")
                }
                false
            }
        }
    }
    
    /**
     * 发送文件消息
     */
    /**
     * 发送图片消息
     */
    suspend fun sendImageMessage(fileName: String, imageBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected) {
                    return@withContext false
                }
                
                val fileDataBase64 = Base64.encodeToString(imageBytes, Base64.DEFAULT)
                
                val message = Message(
                    type = Message.TYPE_FILE,
                    fileType = Message.FILE_TYPE_IMAGES,
                    fileName = generateRandomFileName(fileName),
                    originalFileName = fileName,
                    fileData = fileDataBase64,
                    timestamp = System.currentTimeMillis()
                )
                
                sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "发送图片消息失败", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("发送图片失败: ${e.message}")
                }
                false
            }
        }
    }
    
    suspend fun sendFileMessage(filePath: String, fileName: String, fileType: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isConnected) {
                    return@withContext false
                }
                
                // 读取文件并转换为Base64
                val file = File(filePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        listener?.onError("文件不存在")
                    }
                    return@withContext false
                }
                
                val fileBytes = file.readBytes()
                val fileDataBase64 = Base64.encodeToString(fileBytes, Base64.DEFAULT)
                
                val message = Message(
                    type = Message.TYPE_FILE,
                    fileType = fileType,
                    fileName = generateRandomFileName(fileName),
                    originalFileName = fileName,
                    fileData = fileDataBase64,
                    timestamp = System.currentTimeMillis()
                )
                
                sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "发送文件消息失败", e)
                withContext(Dispatchers.Main) {
                    listener?.onError("发送文件失败: ${e.message}")
                }
                false
            }
        }
    }
    
    /**
     * 发送消息到服务器
     */
    private fun sendMessage(message: Message): Boolean {
        return try {
            val jsonData = gson.toJson(message)
            val messageBytes = jsonData.toByteArray(Charsets.UTF_8)
            
            // 发送消息长度（4字节）
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(messageBytes.size).array()
            outputStream?.write(lengthBytes)
            
            // 发送消息内容
            outputStream?.write(messageBytes)
            outputStream?.flush()
            
            Log.d(TAG, "消息发送成功: ${message.type}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
            false
        }
    }
    
    /**
     * 生成随机文件名
     */
    private fun generateRandomFileName(originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "")
        val randomString = (1..8).map { ('a'..'z').random() }.joinToString("")
        return if (extension.isNotEmpty()) {
            "$randomString.$extension"
        } else {
            randomString
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        disconnect(false)
    }
    
    private fun disconnect(allowReconnect: Boolean = false) {
        clientScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始断开连接...")
                isConnected = false
                
                // 如果是手动断开，清除连接参数防止自动重连
                if (!allowReconnect) {
                    lastConnectionParams = null
                    reconnectAttempts = MAX_RECONNECT_ATTEMPTS // 阻止重连
                }
                
                // 停止心跳包机制
                heartbeatJob?.cancel()
                heartbeatJob = null
                lastHeartbeatTime = 0L
                Log.d(TAG, "心跳包机制已停止")
                
                inputStream?.close()
                Log.d(TAG, "输入流已关闭")
                
                outputStream?.close()
                Log.d(TAG, "输出流已关闭")
                
                socket?.close()
                Log.d(TAG, "Socket已关闭")
                
                inputStream = null
                outputStream = null
                socket = null
                
                withContext(Dispatchers.Main) {
                    listener?.onDisconnected()
                }
                
                Log.d(TAG, "连接已断开")
            } catch (e: Exception) {
                Log.e(TAG, "断开连接时发生错误: ${e.javaClass.simpleName} - ${e.message}", e)
            }
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return isConnected && socket?.isConnected == true
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        clientScope.cancel()
    }
}