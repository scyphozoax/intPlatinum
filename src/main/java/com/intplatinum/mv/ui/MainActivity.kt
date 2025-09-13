package com.intplatinum.mv.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
            Toast.makeText(this, "需要存储权限才能发送图片", Toast.LENGTH_SHORT).show()
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

    private fun setupClickListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 用户列表按钮
        binding.btnUserList.setOnClickListener {
            toggleUserList()
        }
        
        // 设置按钮
        binding.btnSettings.setOnClickListener {
            showCacheManagementDialog()
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
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openImagePicker()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
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
        
        AlertDialog.Builder(this)
            .setTitle("缓存管理")
            .setMessage(message)
            .setPositiveButton("清理过期缓存") { _, _ ->
                lifecycleScope.launch {
                    val deletedCount = imageCacheManager.clearExpiredCache()
                    Toast.makeText(this@MainActivity, "已清理 $deletedCount 个过期文件", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("清理所有缓存") { _, _ ->
                AlertDialog.Builder(this)
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
}