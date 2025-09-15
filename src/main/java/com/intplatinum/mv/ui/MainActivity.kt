package com.intplatinum.mv.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.intplatinum.mv.R
import com.intplatinum.mv.data.ChatMessage
import com.intplatinum.mv.data.MessageType
import com.intplatinum.mv.data.UserInfo
import com.intplatinum.mv.databinding.ActivityMainBinding
import com.intplatinum.mv.network.ChatClient
import com.intplatinum.mv.network.ChatClientManager
import com.intplatinum.mv.ui.adapter.MessageAdapter
import com.intplatinum.mv.ui.adapter.UserAdapter
import com.intplatinum.mv.utils.ImageCacheManager
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var chatClient: ChatClient
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var userAdapter: UserAdapter
    private lateinit var currentUsername: String
    private var isUserListVisible = false
    private lateinit var imageCacheManager: ImageCacheManager

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                sendImageMessage(uri)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            showPermissionDeniedDialog()
        }
    }

    private val multiplePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openImagePicker()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取传递的数据
        chatClient = ChatClientManager.getChatClient() ?: run {
            Toast.makeText(this, "连接已断开，请重新登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUsername = intent.getStringExtra("username") ?: ""

        setupUI()
        setupClickListeners()
        setupChatClientListener()
        startMessageListener()
    }

    private fun setupUI() {
        // 设置标题
        binding.tvTitle.text = "聊天室 - $currentUsername"

        // 设置消息列表
        messageAdapter = MessageAdapter(currentUsername)
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        // 设置用户列表
        userAdapter = UserAdapter { user ->
            // 点击用户的处理逻辑
            Toast.makeText(this, "点击了用户: ${user.username}", Toast.LENGTH_SHORT).show()
        }
        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = userAdapter
        }

        // 初始隐藏用户列表
        binding.layoutUserList.visibility = View.GONE
        
        // 初始化图片缓存管理器
        imageCacheManager = ImageCacheManager(this)
    }

    private fun setupChatClientListener() {
        chatClient.setListener(object : ChatClient.ChatClientListener {
            override fun onConnected() {
                // 连接成功处理
            }

            override fun onDisconnected() {
                // 断开连接处理
            }

            override fun onMessageReceived(message: com.intplatinum.mv.data.Message) {
                // 处理用户列表消息
                if (message.type == "user_list") {
                    try {
                        val gson = com.google.gson.Gson()
                        val userListType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                        val userList: List<String> = gson.fromJson(message.content, userListType)
                        
                        runOnUiThread {
                            val userInfoList = userList.map { username ->
                                UserInfo(username)
                            }
                            userAdapter.updateUsers(userInfoList)
                            updateUserListTitle()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                // 处理弹窗消息
                else if (message.type == com.intplatinum.mv.data.Message.TYPE_POPUP_MESSAGE) {
                    runOnUiThread {
                        showPopupMessage(message.content ?: "")
                    }
                }
                // 处理弹窗公告
                else if (message.type == com.intplatinum.mv.data.Message.TYPE_POPUP_ANNOUNCEMENT) {
                    runOnUiThread {
                        showPopupAnnouncement(message.content ?: "")
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "连接错误: $error", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onVersionMismatch(requiredVersion: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "版本不匹配，需要版本: $requiredVersion", Toast.LENGTH_LONG).show()
                }
            }

            override fun onBanned(message: String) {
                runOnUiThread {
                    showBannedDialog(message)
                }
            }
            
            override fun onServerShutdown(message: String) {
                runOnUiThread {
                    showServerShutdownDialog(message)
                }
            }
        })
    }

    private fun setupClickListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            // 断开连接并返回到输入地址和昵称页面
            if (::chatClient.isInitialized) {
                chatClient.disconnect()
            }
            // 启动LoginActivity并清除任务栈
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        // 用户列表按钮
        binding.btnUserList.setOnClickListener {
            toggleUserList()
        }
        
        // 设置按钮
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // 发送按钮
        binding.btnSend.setOnClickListener {
            sendTextMessage()
        }

        // 附件按钮
        binding.btnAttach.setOnClickListener {
            checkPermissionAndOpenImagePicker()
        }

        // 输入框回车发送
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendTextMessage()
            true
        }
    }

    private fun toggleUserList() {
        isUserListVisible = !isUserListVisible
        binding.layoutUserList.visibility = if (isUserListVisible) View.VISIBLE else View.GONE
        
        // 更新用户列表标题显示在线用户数
        updateUserListTitle()
    }

    private fun updateUserListTitle() {
        val onlineCount = userAdapter.getUserCount()
        binding.tvTitle.text = if (isUserListVisible) {
            "在线用户 ($onlineCount)"
        } else {
            "聊天室 - $currentUsername"
        }
    }

    private fun sendTextMessage() {
        val messageText = binding.etMessage.text.toString().trim()
        if (messageText.isEmpty()) return

        lifecycleScope.launch {
            try {
                chatClient.sendTextMessage(messageText)
                binding.etMessage.setText("")
                
                // 添加到本地消息列表
                val message = ChatMessage(
                    type = MessageType.TEXT,
                    sender = currentUsername,
                    content = messageText,
                    timestamp = System.currentTimeMillis()
                )
                messageAdapter.addMessage(message)
                scrollToBottom()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissionAndOpenImagePicker() {
        // 首次点击时显示权限说明对话框
        if (!hasRequestedPermissionBefore()) {
            showPermissionExplanationDialog()
            return
        }
        
        // 检查权限并请求
        if (hasImagePermission()) {
            openImagePicker()
        } else {
            requestImagePermission()
        }
    }
    
    private fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用 READ_MEDIA_IMAGES
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 及以下使用 READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestImagePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 请求 READ_MEDIA_IMAGES
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            // Android 12 及以下请求 READ_EXTERNAL_STORAGE
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    
    private fun hasRequestedPermissionBefore(): Boolean {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return prefs.getBoolean("has_requested_image_permission", false)
    }
    
    private fun setPermissionRequested() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("has_requested_image_permission", true).apply()
    }
    
    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("图片发送权限")
            .setMessage("为了发送图片，应用需要访问您的相册。请在下一步中允许访问权限。")
            .setPositiveButton("继续") { _, _ ->
                setPermissionRequested()
                requestImagePermission()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("权限被拒绝")
            .setMessage("无法发送图片，因为没有访问相册的权限。您可以：\n\n1. 重新授权\n2. 前往系统设置手动开启权限")
            .setPositiveButton("重新授权") { _, _ ->
                requestImagePermission()
            }
            .setNegativeButton("前往设置") { _, _ ->
                openAppSettings()
            }
            .setNeutralButton("取消", null)
            .show()
    }
    
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        imagePickerLauncher.launch(intent)
    }

    private fun sendImageMessage(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 读取图片文件
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()

                if (bytes != null) {
                    // 获取文件名
                    val fileName = getFileName(uri) ?: "image.jpg"
                    
                    chatClient.sendImageMessage(fileName, bytes)
                    
                    // 添加到本地消息列表
                    val message = ChatMessage(
                        type = MessageType.IMAGE,
                        sender = currentUsername,
                        content = "[图片]",
                        timestamp = System.currentTimeMillis(),
                        fileName = fileName,
                        filePath = uri.toString()
                    )
                    messageAdapter.addMessage(message)
                    scrollToBottom()
                } else {
                    Toast.makeText(this@MainActivity, "读取图片失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "发送图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                it.getString(nameIndex)
            } else null
        }
    }

    private fun startMessageListener() {
        lifecycleScope.launch {
            chatClient.messageFlow.collect { message: ChatMessage ->
                when (message.type) {
                    MessageType.TEXT, MessageType.IMAGE -> {
                        if (message.sender != currentUsername) {
                            messageAdapter.addMessage(message)
                            scrollToBottom()
                        }
                    }
                    MessageType.SYSTEM -> {
                        messageAdapter.addMessage(message)
                        scrollToBottom()
                        
                        // 处理用户加入/离开消息
                        handleSystemMessage(message.content)
                    }
                }
            }
        }
    }

    private fun handleSystemMessage(content: String) {
        when {
            content.contains("加入了聊天室") -> {
                val username = content.substringBefore(" 加入了聊天室")
                if (username != currentUsername) {
                    userAdapter.addUser(UserInfo(username))
                    updateUserListTitle()
                }
            }
            content.contains("离开了聊天室") -> {
                val username = content.substringBefore(" 离开了聊天室")
                userAdapter.removeUser(username)
                updateUserListTitle()
            }
        }
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            if (messageAdapter.itemCount > 0) {
                binding.rvMessages.smoothScrollToPosition(messageAdapter.itemCount - 1)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 应用进入后台时不断开连接，保持心跳包机制运行
    }
    
    override fun onResume() {
        super.onResume()
        // 应用回到前台时检查连接状态
        if (!chatClient.isConnected()) {
            Toast.makeText(this, "连接已断开，请重新登录", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不在这里断开连接，让ChatClientManager统一管理连接生命周期
        // chatClient.disconnect()
    }

    override fun onBackPressed() {
        if (isUserListVisible) {
            toggleUserList()
        } else {
            super.onBackPressed()
        }
    }
    
    private fun showCacheManagementDialog() {
        val cacheInfo = imageCacheManager.getCacheInfo()
        val message = "缓存信息:\n" +
                "文件数量: ${cacheInfo.fileCount}\n" +
                "缓存大小: ${String.format("%.2f", cacheInfo.getTotalSizeMB())} MB\n" +
                "使用率: ${String.format("%.1f", cacheInfo.getUsagePercentage())}%\n" +
                "最大大小: ${String.format("%.0f", cacheInfo.getMaxSizeMB())} MB"
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("缓存管理")
            .setMessage(message)
            .setPositiveButton("清理过期缓存") { _, _ ->
                lifecycleScope.launch {
                    val deletedCount = imageCacheManager.clearExpiredCache()
                    Toast.makeText(this@MainActivity, "已清理 $deletedCount 个过期文件", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("清理所有缓存") { _, _ ->
                AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle("确认清理")
                    .setMessage("确定要清理所有图片缓存吗？这将删除所有已下载的图片。")
                    .setPositiveButton("确定") { _, _ ->
                        lifecycleScope.launch {
                            val success = imageCacheManager.clearAllCache()
                            val message = if (success) "缓存清理完成" else "缓存清理失败"
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("智能清理") { _, _ ->
                lifecycleScope.launch {
                    val (expiredDeleted, oversizedDeleted) = imageCacheManager.smartCleanCache()
                    val message = "智能清理完成\n过期文件: $expiredDeleted 个\n超大文件: $oversizedDeleted 个"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }
    
    /**
     * 显示弹窗消息
     */
    private fun showPopupMessage(content: String) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("服务器消息")
            .setMessage(content)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton("确定", null)
            .show()
    }
    
    /**
     * 显示弹窗公告
     */
    private fun showPopupAnnouncement(content: String) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("服务器公告")
            .setMessage(content)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton("我已了解", null)
            .show()
    }

    private fun showBannedDialog(message: String) {
        // 解析和清理封禁消息，提供更友好的用户体验
        val cleanMessage = parseBannedMessage(message)
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("🚫 访问受限")
            .setMessage("$cleanMessage\n\n如有疑问，请联系服务器管理员。\n\n点击确定退出intPlatinum。")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
                // 断开连接
                if (::chatClient.isInitialized) {
                    chatClient.disconnect()
                }
                // 返回到输入地址和昵称页面
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 解析封禁消息，提取有用信息并格式化为用户友好的文本
     */
    private fun parseBannedMessage(message: String): String {
        return try {
            // 尝试解析JSON格式的消息
            if (message.startsWith("{") && message.endsWith("}")) {
                val jsonObject = org.json.JSONObject(message)
                if (jsonObject.has("content")) {
                    return jsonObject.getString("content")
                } else if (jsonObject.has("type") && jsonObject.getString("type") == "banned") {
                    return "您的IP地址已被该服务器封禁"
                }
            }
            
            // 如果消息包含技术性内容，提取关键信息
            when {
                message.lowercase().contains("banned") -> "您的IP地址已被该服务器封禁"
                message.lowercase().contains("ip") && (message.contains("禁") || message.lowercase().contains("ban")) -> "您的IP地址已被该服务器封禁"
                else -> message // 如果是普通文本消息，直接返回
            }
        } catch (e: Exception) {
            // 解析失败时返回默认消息
            "您已被该服务器封禁"
        }
    }
    
    private fun showServerShutdownDialog(message: String) {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("服务器关闭")
            .setMessage("服务器已关闭，请稍后再试")
            .setPositiveButton("确定") { _, _ ->
                // 返回到输入地址和昵称页面
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setCancelable(false)
            .show()
    }
}