package com.intplatinum.mv.ui

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.intplatinum.mv.databinding.ActivityImageViewBinding
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
            
            // 加载图片
            Glide.with(this)
                .load(File(imagePath))
                .into(binding.ivFullImage)
                
            // 设置点击事件关闭Activity
            binding.ivFullImage.setOnClickListener {
                finish()
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
    
    private fun downloadImage(imagePath: String) {
        try {
            val sourceFile = File(imagePath)
            if (!sourceFile.exists()) {
                Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            
            val fileName = "ChatImage_${System.currentTimeMillis()}.jpg"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上使用MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Toast.makeText(this, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Android 9及以下直接保存到Pictures目录
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val destFile = File(picturesDir, fileName)
                
                FileInputStream(sourceFile).use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
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