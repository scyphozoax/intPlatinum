package com.intplatinum.mv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.intplatinum.mv.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding
    
    companion object {
        private const val APP_VERSION = "v1.0.2a"
        private const val WEBSITE_URL = "https://scy.la/intplatinum/"
        private const val GITHUB_URL = "https://github.com/scyphozoax/intPlatinum"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupClickListeners()
    }
    
    private fun setupUI() {
        // 设置版本号
        binding.tvVersion.text = "版本 $APP_VERSION"
    }
    
    private fun setupClickListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // 网站链接
        binding.tvWebsiteLink.setOnClickListener {
            openUrl(WEBSITE_URL)
        }
        
        // GitHub链接
        binding.layoutGitHubLink.setOnClickListener {
            openUrl(GITHUB_URL)
        }
    }
    
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }
}