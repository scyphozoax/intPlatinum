package com.intplatinum.mv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.intplatinum.mv.R
import com.intplatinum.mv.databinding.ActivityLoginBinding
import com.intplatinum.mv.network.ChatClient
import com.intplatinum.mv.network.ChatClientManager
import com.intplatinum.mv.utils.ConnectionPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.InetSocketAddress

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var chatClient: ChatClient
    private lateinit var connectionPreferences: ConnectionPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        // 初始化连接设置管理器
        connectionPreferences = ConnectionPreferences(this)
        
        // 加载保存的设置
        loadSavedSettings()
    }
    
    private fun loadSavedSettings() {
        val settings = connectionPreferences.getConnectionSettings()
        
        if (settings.rememberSettings && settings.lastConnectionSuccess) {
            // 如果记住设置且上次连接成功，则填充保存的数据
            binding.etServerAddress.setText("${settings.serverIp}:${settings.serverPort}")
            binding.etUsername.setText(settings.username)
            binding.cbRememberSettings.isChecked = true
            binding.tvClearSettings.visibility = View.VISIBLE
        } else {
            // 否则使用默认设置
            binding.etServerAddress.setText("localhost:7995")
            binding.cbRememberSettings.isChecked = false
            binding.tvClearSettings.visibility = View.GONE
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            connectToServer()
        }
        
        // 记住设置复选框
        binding.cbRememberSettings.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.tvClearSettings.visibility = View.VISIBLE
            } else {
                binding.tvClearSettings.visibility = View.GONE
                // 如果取消记住设置，清除连接成功标记
                connectionPreferences.clearLastConnectionSuccess()
            }
        }
        
        // 清除设置按钮
        binding.tvClearSettings.setOnClickListener {
            clearSavedSettings()
        }
    }
    
    private fun clearSavedSettings() {
        connectionPreferences.clearAllSettings()
        binding.etServerAddress.setText("localhost:7995")
        binding.etUsername.setText("")
        binding.cbRememberSettings.isChecked = false
        binding.tvClearSettings.visibility = View.GONE
        Toast.makeText(this, "已清除保存的设置", Toast.LENGTH_SHORT).show()
    }

    private fun connectToServer() {
        val serverAddress = binding.etServerAddress.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()

        if (serverAddress.isEmpty()) {
            showError("请输入服务器地址")
            return
        }

        if (username.isEmpty()) {
            showError("请输入用户名")
            return
        }

        if (username.length > 20) {
            showError("用户名不能超过20个字符")
            return
        }

        // 解析服务器地址和端口
        val parts = serverAddress.split(":")
        if (parts.size != 2) {
            showError("服务器地址格式错误，请使用 host:port 格式")
            return
        }

        val host = parts[0]
        val port = parts[1].toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            showError("端口号无效")
            return
        }

        Log.d("LoginActivity", "开始连接服务器: $host:$port, 用户名: $username")
        showLoading(true)
        
        lifecycleScope.launch {
            try {
                Log.d("LoginActivity", "开始连接到服务器: $host:$port")
                binding.tvStatus.text = "正在连接服务器..."
                
                chatClient = ChatClient(this@LoginActivity)
                Log.d("LoginActivity", "正在尝试连接...")
                val success = chatClient.connect(host, port, username)
                Log.d("LoginActivity", "连接结果: $success")
                
                if (success) {
                    showLoading(false)
                    Log.d("LoginActivity", "连接成功，跳转到主界面")
                    
                    // 如果勾选了记住设置，保存连接设置
                    if (binding.cbRememberSettings.isChecked) {
                        connectionPreferences.saveConnectionSettings(
                            serverIp = host,
                            serverPort = port,
                            username = username,
                            rememberSettings = true
                        )
                        connectionPreferences.markLastConnectionSuccess()
                    }
                    
                    // 连接成功，跳转到主界面
                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    intent.putExtra("username", username)
                    // 将chatClient保存到Application或使用单例模式
                    ChatClientManager.setChatClient(chatClient)
                    startActivity(intent)
                    finish()
                } else {
                    showLoading(false)
                    Log.e("LoginActivity", "连接失败")
                    // 连接失败时清除连接成功标记
                    connectionPreferences.clearLastConnectionSuccess()
                    showError("无法连接到服务器，请检查网络连接和服务器状态后重新尝试")
                }
            } catch (e: Exception) {
                showLoading(false)
                Log.e("LoginActivity", "连接异常", e)
                showError("连接失败: ${e.message ?: "未知错误"}\n\n请检查网络连接后重新尝试")
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !show
        binding.etServerAddress.isEnabled = !show
        binding.etUsername.isEnabled = !show

        if (show) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "正在连接..."
        } else {
            binding.tvStatus.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
    }
    


    override fun onDestroy() {
        super.onDestroy()
        // 只有在连接失败或应用真正退出时才断开连接
        // 正常跳转到MainActivity时不应该断开连接
        // if (::chatClient.isInitialized) {
        //     chatClient.disconnect()
        // }
    }
}