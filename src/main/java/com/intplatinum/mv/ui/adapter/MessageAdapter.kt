package com.intplatinum.mv.ui.adapter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.intplatinum.mv.R
import com.intplatinum.mv.data.ChatMessage
import com.intplatinum.mv.data.MessageType
import com.intplatinum.mv.databinding.ItemMessageBinding
import com.intplatinum.mv.ui.ImageViewActivity
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val currentUsername: String
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun addMessages(newMessages: List<ChatMessage>) {
        val startPosition = messages.size
        messages.addAll(newMessages)
        notifyItemRangeInserted(startPosition, newMessages.size)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }
    
    private fun downloadImageToGallery(context: Context, imagePath: String) {
        try {
            // 处理URI格式的图片路径
            val inputStream = if (imagePath.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(imagePath))
            } else {
                val sourceFile = File(imagePath)
                if (!sourceFile.exists()) {
                    Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
                    return
                }
                FileInputStream(sourceFile)
            }
            
            if (inputStream == null) {
                Toast.makeText(context, "无法读取图片文件", Toast.LENGTH_SHORT).show()
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
                
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        inputStream.use { input ->
                            input.copyTo(outputStream)
                        }
                    }
                    Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
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
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                
                Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            // 隐藏所有布局
            binding.layoutSentMessage.visibility = View.GONE
            binding.layoutReceivedMessage.visibility = View.GONE
            binding.layoutSystemMessage.visibility = View.GONE

            when (message.type) {
                MessageType.SYSTEM -> {
                    binding.layoutSystemMessage.visibility = View.VISIBLE
                    binding.tvSystemMessage.text = message.content
                }
                MessageType.TEXT -> {
                    if (message.sender == currentUsername) {
                        bindSentMessage(message)
                    } else {
                        bindReceivedMessage(message)
                    }
                }
                MessageType.IMAGE -> {
                    // 图片消息处理
                    if (message.sender == currentUsername) {
                        bindSentImageMessage(message)
                    } else {
                        bindReceivedImageMessage(message)
                    }
                }
            }
        }

        private fun bindSentMessage(message: ChatMessage) {
            binding.layoutSentMessage.visibility = View.VISIBLE
            binding.tvSentMessage.text = message.content
            binding.tvSentMessage.visibility = View.VISIBLE
            binding.ivSentImage.visibility = View.GONE
            binding.tvSentTime.text = dateFormat.format(Date(message.timestamp))
        }

        private fun bindReceivedMessage(message: ChatMessage) {
            binding.layoutReceivedMessage.visibility = View.VISIBLE
            binding.tvSenderName.text = message.sender
            binding.tvReceivedMessage.text = message.content
            binding.tvReceivedMessage.visibility = View.VISIBLE
            binding.ivReceivedImage.visibility = View.GONE
            binding.tvReceivedTime.text = dateFormat.format(Date(message.timestamp))
        }

        private fun bindSentImageMessage(message: ChatMessage) {
            binding.layoutSentMessage.visibility = View.VISIBLE
            binding.tvSentMessage.visibility = View.GONE
            binding.ivSentImage.visibility = View.VISIBLE
            binding.tvSentTime.text = dateFormat.format(Date(message.timestamp))

            // 加载图片（优化版本：缩略图+内存优化）
            if (message.filePath != null) {
                Glide.with(binding.root.context)
                    .load(message.filePath)
                    .thumbnail(0.1f)  // 先加载10%大小的缩略图
                    .override(280, 280)  // 限制加载尺寸，防止内存溢出
                    .centerCrop()
                    .placeholder(R.drawable.ic_attach_file)
                    .error(R.drawable.ic_attach_file)
                    .into(binding.ivSentImage)
                    
                // 设置点击事件
                binding.ivSentImage.setOnClickListener {
                    val intent = Intent(binding.root.context, ImageViewActivity::class.java)
                    intent.putExtra(ImageViewActivity.EXTRA_IMAGE_PATH, message.filePath)
                    intent.putExtra(ImageViewActivity.EXTRA_SENDER_NAME, message.sender)
                    binding.root.context.startActivity(intent)
                }
                
                // 设置长按事件
                binding.ivSentImage.setOnLongClickListener {
                    downloadImageToGallery(binding.root.context, message.filePath!!)
                    true
                }
            }
        }

        private fun bindReceivedImageMessage(message: ChatMessage) {
            binding.layoutReceivedMessage.visibility = View.VISIBLE
            binding.tvSenderName.text = message.sender
            binding.tvReceivedMessage.visibility = View.GONE
            binding.ivReceivedImage.visibility = View.VISIBLE
            binding.tvReceivedTime.text = dateFormat.format(Date(message.timestamp))

            // 加载图片（优化版本：缩略图+内存优化）
            if (message.filePath != null) {
                Glide.with(binding.root.context)
                    .load(message.filePath)
                    .thumbnail(0.1f)  // 先加载10%大小的缩略图
                    .override(280, 280)  // 限制加载尺寸，防止内存溢出
                    .centerCrop()
                    .placeholder(R.drawable.ic_attach_file)
                    .error(R.drawable.ic_attach_file)
                    .into(binding.ivReceivedImage)
                    
                // 设置点击事件
                binding.ivReceivedImage.setOnClickListener {
                    val intent = Intent(binding.root.context, ImageViewActivity::class.java)
                    intent.putExtra(ImageViewActivity.EXTRA_IMAGE_PATH, message.filePath)
                    intent.putExtra(ImageViewActivity.EXTRA_SENDER_NAME, message.sender)
                    binding.root.context.startActivity(intent)
                }
                
                // 设置长按事件
                binding.ivReceivedImage.setOnLongClickListener {
                    downloadImageToGallery(binding.root.context, message.filePath!!)
                    true
                }
            }
        }
    }
}