package com.intplatinum.mv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.intplatinum.mv.R
import com.intplatinum.mv.databinding.ActivitySettingsBinding
import com.intplatinum.mv.utils.ImageCacheManager

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var imageCacheManager: ImageCacheManager
    
    companion object {
        private const val APP_VERSION = "v1.0.2a"
        private const val GITHUB_URL = "https://github.com/scyphozoax/intPlatinum"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        imageCacheManager = ImageCacheManager(this)
        
        setupUI()
        setupClickListeners()
    }
    
    private fun setupUI() {
        // 设置版本号
        binding.tvVersionNumber.text = APP_VERSION
    }
    
    private fun setupClickListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // 缓存管理
        binding.layoutCacheManagement.setOnClickListener {
            showCacheManagementDialog()
        }
        
        // 版本信息（点击显示详细信息）
        binding.layoutVersionInfo.setOnClickListener {
            showVersionInfoDialog()
        }
        
        // 关于页面
        binding.layoutAbout.setOnClickListener {
            val intent = Intent(this, AboutActivity::class.java)
            startActivity(intent)
        }
        
        // GitHub页面
        binding.layoutGitHub.setOnClickListener {
            openGitHubPage()
        }
    }
    
    private fun showCacheManagementDialog() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("缓存管理")
            .setMessage("确定要清理所有缓存数据吗？这将删除所有已下载的图片缓存。")
            .setPositiveButton("确定") { _, _ ->
                clearCache()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun clearCache() {
        try {
            val success = imageCacheManager.clearAllCache()
            val message = if (success) "缓存清理完成" else "缓存清理失败"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "缓存清理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showVersionInfoDialog() {
        val versionInfo = """
            应用版本: $APP_VERSION
            
            构建信息:
            • 目标SDK: Android 14 (API 34)
            • 最低SDK: Android 7.0 (API 24)
            • 构建工具: Android Gradle Plugin
            
            更新日志:
            • 优化用户界面
            • 修复已知问题
            • 提升稳定性
        """.trimIndent()
        
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("版本信息")
            .setMessage(versionInfo)
            .setPositiveButton("确定", null)
            .show()
    }
    
    private fun openGitHubPage() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }
}