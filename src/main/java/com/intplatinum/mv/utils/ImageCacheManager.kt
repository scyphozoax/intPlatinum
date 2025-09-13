package com.intplatinum.mv.utils

import android.content.Context
import java.io.File

/**
 * 图片缓存管理工具类
 */
class ImageCacheManager(private val context: Context) {
    
    companion object {
        private const val CACHE_DIR_NAME = "images"
        private const val MAX_CACHE_SIZE = 100 * 1024 * 1024L // 100MB
        private const val MAX_CACHE_AGE = 7 * 24 * 60 * 60 * 1000L // 7天
    }
    
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * 获取缓存目录
     */
    fun getImageCacheDir(): File = cacheDir
    
    /**
     * 获取缓存大小（字节）
     */
    fun getCacheSize(): Long {
        return calculateDirectorySize(cacheDir)
    }
    
    /**
     * 获取缓存文件数量
     */
    fun getCacheFileCount(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }
    
    /**
     * 清理所有缓存
     */
    fun clearAllCache(): Boolean {
        return try {
            cacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 清理过期缓存
     */
    fun clearExpiredCache(): Int {
        val currentTime = System.currentTimeMillis()
        var deletedCount = 0
        
        cacheDir.listFiles()?.forEach { file ->
            if (currentTime - file.lastModified() > MAX_CACHE_AGE) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        
        return deletedCount
    }
    
    /**
     * 清理超出大小限制的缓存（删除最旧的文件）
     */
    fun clearOversizedCache(): Int {
        val currentSize = getCacheSize()
        if (currentSize <= MAX_CACHE_SIZE) {
            return 0
        }
        
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return 0
        var deletedCount = 0
        var sizeToDelete = currentSize - MAX_CACHE_SIZE
        
        for (file in files) {
            if (sizeToDelete <= 0) break
            
            val fileSize = file.length()
            if (file.delete()) {
                deletedCount++
                sizeToDelete -= fileSize
            }
        }
        
        return deletedCount
    }
    
    /**
     * 智能清理缓存（先清理过期，再清理超大）
     */
    fun smartCleanCache(): Pair<Int, Int> {
        val expiredDeleted = clearExpiredCache()
        val oversizedDeleted = clearOversizedCache()
        return Pair(expiredDeleted, oversizedDeleted)
    }
    
    /**
     * 获取缓存信息
     */
    fun getCacheInfo(): CacheInfo {
        return CacheInfo(
            fileCount = getCacheFileCount(),
            totalSize = getCacheSize(),
            maxSize = MAX_CACHE_SIZE,
            maxAge = MAX_CACHE_AGE
        )
    }
    
    /**
     * 计算目录大小
     */
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }
    
    /**
     * 缓存信息数据类
     */
    data class CacheInfo(
        val fileCount: Int,
        val totalSize: Long,
        val maxSize: Long,
        val maxAge: Long
    ) {
        fun getTotalSizeMB(): Double = totalSize / (1024.0 * 1024.0)
        fun getMaxSizeMB(): Double = maxSize / (1024.0 * 1024.0)
        fun getUsagePercentage(): Double = if (maxSize > 0) (totalSize.toDouble() / maxSize) * 100 else 0.0
    }
}