package com.ai.assistance.operit.core

import java.util.UUID

/**
 * 消息重要性等级
 */
enum class MessageImportance {
    CRITICAL,   // 关键信息，永不压缩
    HIGH,       // 高重要性
    MEDIUM,     // 中等重要性
    LOW,        // 低重要性，可压缩
    TRANSIENT   // 临时信息，可丢弃
}

/**
 * 消息数据类
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val role: String,
    val timestamp: Long = System.currentTimeMillis(),
    val importance: MessageImportance = MessageImportance.MEDIUM,
    var tokens: Int = content.length / 4, // 简化估算
    var summary: String? = null,
    var isProtected: Boolean = false
)

/**
 * 智能上下文管理器
 * 
 * 功能:
 * 1. 自动评估消息重要性
 * 2. 智能压缩历史消息
 * 3. 动态调整上下文窗口
 * 4. 保护关键信息不被压缩
 */
class SmartContextManager(
    private val maxTokens: Int = 4096,
    private val compressionThreshold: Float = 0.8f
) {
    private val messages = mutableListOf<Message>()
    
    /**
     * 添加消息并自动评估重要性
     */
    fun addMessage(content: String, role: String, importance: MessageImportance? = null): Message {
        val msg = Message(
            content = content,
            role = role,
            importance = importance ?: evaluateImportance(content, role)
        )
        messages.add(msg)
        autoCompress()
        return msg
    }
    
    /**
     * 评估消息重要性
     */
    private fun evaluateImportance(content: String, role: String): MessageImportance {
        // 系统消息通常重要
        if (role == "system") return MessageImportance.CRITICAL
        
        // 检测关键信息关键词
        val criticalKeywords = listOf("密码", "token", "key", "重要", "记住", "password")
        if (criticalKeywords.any { content.lowercase().contains(it) }) {
            return MessageImportance.CRITICAL
        }
        
        // 问题通常重要
        if (content.contains("?") || content.contains("？")) {
            return MessageImportance.HIGH
        }
        
        // 短消息可能不重要
        if (content.length < 50) return MessageImportance.LOW
        
        return MessageImportance.MEDIUM
    }
    
    /**
     * 自动压缩上下文
     */
    private fun autoCompress() {
        val totalTokens = messages.sumOf { it.tokens }
        if (totalTokens > maxTokens * compressionThreshold) {
            compressOldMessages()
        }
    }
    
    /**
     * 压缩旧消息
     */
    private fun compressOldMessages() {
        val compressible = messages.filter { 
            it.importance <= MessageImportance.LOW && !it.isProtected 
        }
        
        compressible.take(3).forEach { msg ->
            msg.summary = summarizeMessage(msg)
            msg.content = msg.summary!!
            msg.tokens = msg.content.length / 4
        }
    }
    
    private fun summarizeMessage(message: Message): String {
        return if (message.content.length <= 50) {
            message.content
        } else {
            message.content.take(50) + "..."
        }
    }
    
    /**
     * 获取优化后的上下文
     */
    fun getContext(maxTokens: Int = this.maxTokens): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        var totalTokens = 0
        
        // 从最新消息开始，倒序添加
        for (msg in messages.reversed()) {
            if (totalTokens + msg.tokens > maxTokens) break
            result.add(0, mapOf(
                "role" to msg.role,
                "content" to msg.content
            ))
            totalTokens += msg.tokens
        }
        
        return result
    }
    
    /**
     * 保护消息不被压缩
     */
    fun protectMessage(messageId: String) {
        messages.find { it.id == messageId }?.let { msg ->
            msg.isProtected = true
        }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalMessages" to messages.size,
            "totalTokens" to messages.sumOf { it.tokens },
            "compressedCount" to messages.count { it.summary != null },
            "protectedCount" to messages.count { it.isProtected }
        )
    }
}
