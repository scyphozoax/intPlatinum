package com.intplatinum.mv.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.intplatinum.mv.R
import com.intplatinum.mv.databinding.ActivityImageViewBinding
import com.intplatinum.mv.ui.widget.ZoomableImageView
import java.io.File
import java.io.FileInputStream

class ImageViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageViewBinding
    
    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_SENDER_NAME = "sender_name"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val senderName = intent.getStringExtra(EXTRA_SENDER_NAME)
        
        if (imagePath != null) {
            // 设置标题
            binding.tvTitle.text = if (senderName != null) "来自 $senderName 的图片" else "图片详情"
            
            // 加载图片到可缩放的ImageView
            // 支持文件路径和URI格式
            val imageSource = if (imagePath.startsWith("content://") || imagePath.startsWith("file://")) {
                // URI格式，直接使用
                imagePath
            } else {
                // 文件路径格式，转换为File对象
                File(imagePath)
            }
            
            Glide.with(this)
                .load(imageSource)
                .placeholder(R.drawable.ic_attach_file)
                .error(R.drawable.ic_attach_file)
                .into(binding.ivFullImage)
                
            // 设置点击事件切换UI显示/隐藏
            binding.ivFullImage.setOnClickListener {
                toggleUIVisibility()
            }
            
            // 设置返回按钮
            binding.btnBack.setOnClickListener {
                finish()
            }
            
            // 设置下载按钮
            binding.btnDownload.setOnClickListener {
                downloadImage(imagePath)
            }
        } else {
            finish()
        }
    }
    
    /**
     * 切换UI显示/隐藏状态
     */
    private fun toggleUIVisibility() {
        val isVisible = binding.layoutHeader.visibility == View.VISIBLE
        binding.layoutHeader.visibility = if (isVisible) View.GONE else View.VISIBLE
        
        // 同时切换状态栏显示
        if (isVisible) {
            // 隐藏状态栏，进入沉浸式模式
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } else {
            // 显示状态栏
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    
    private fun downloadImage(imagePath: String) {
        try {
            // 处理URI格式的图片路径
            val inputStream = if (imagePath.startsWith("content://")) {
                contentResolver.openInputStream(android.net.Uri.parse(imagePath))
            } else {
                val sourceFile = File(imagePath)
                if (!sourceFile.exists()) {
                    Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
                    return
                }
                FileInputStream(sourceFile)
            }
            
            if (inputStream == null) {
                Toast.makeText(this, "无法读取图片文件", Toast.LENGTH_SHORT).show()
                return
            }
            
            val fileName = "ChatImage_${System.currentTimeMillis()}.jpg"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上使用MediaStore API，保存到DCIM目录
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ChatImages")
                }
                
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        inputStream.use { input ->
                            input.copyTo(outputStream)
                        }
                    }
                    Toast.makeText(this, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Android 9及以下直接保存到DCIM目录
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val chatImagesDir = File(dcimDir, "ChatImages")
                if (!chatImagesDir.exists()) {
                    chatImagesDir.mkdirs()
                }
                val destFile = File(chatImagesDir, fileName)
                
                inputStream.use { input ->
                    destFile.outputStream().use { outputStream ->
                        input.copyTo(outputStream)
                    }
                }
                
                // 通知媒体扫描器
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                
                Toast.makeText(this, "图片已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}