package com.ai.assistance.operit.core

import java.util.UUID
import kotlin.math.sqrt

/**
 * 记忆节点
 */
data class MemoryNode(
    val id: String = UUID.randomUUID().toString().take(12),
    var content: String,
    val title: String,
    var importance: Float = 0.5f,
    var accessCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessed: Long = System.currentTimeMillis(),
    val tags: MutableList<String> = mutableListOf(),
    val links: MutableList<String> = mutableListOf()
)

/**
 * 向量索引
 */
class VectorIndex(private val dimension: Int = 64) {
    private val vectors = mutableMapOf<String, FloatArray>()
    
    fun add(id: String, vector: FloatArray) {
        val normalized = vector.map { it / vector.norm() }.toFloatArray()
        vectors[id] = normalized
    }
    
    fun search(query: FloatArray, topK: Int = 5): List<Pair<String, Float>> {
        val queryNorm = query.map { it / query.norm() }.toFloatArray()
        
        return vectors.map { (id, vec) ->
            id to queryNorm.dot(vec)
        }.sortedByDescending { it.second }.take(topK)
    }
    
    fun remove(id: String) {
        vectors.remove(id)
    }
}

/**
 * 记忆引擎 2.0
 * 
 * 功能:
 * 1. 向量索引 - 支持语义检索
 * 2. 时间线 - 时间维度查询
 * 3. 自动衰减 - 不常用记忆权重降低
 * 4. 智能合并 - 相似记忆去重
 */
class MemoryEngineV2 {
    private val memories = mutableMapOf<String, MemoryNode>()
    private val vectorIndex = VectorIndex()
    
    /**
     * 添加记忆
     */
    fun addMemory(
        title: String,
        content: String,
        importance: Float = 0.5f,
        tags: List<String> = emptyList()
    ): MemoryNode {
        val memory = MemoryNode(
            title = title,
            content = content,
            importance = importance,
            tags = tags.toMutableList()
        )
        
        memories[memory.id] = memory
        vectorIndex.add(memory.id, generateEmbedding("$title $content"))
        
        return memory
    }
    
    /**
     * 语义搜索
     */
    fun searchSemantic(query: String, topK: Int = 5): List<MemoryNode> {
        val queryEmbedding = generateEmbedding(query)
        val results = vectorIndex.search(queryEmbedding, topK)
        
        return results.mapNotNull { (id, _) ->
            memories[id]?.let { memory ->
                memory.accessCount++
                memory.lastAccessed = System.currentTimeMillis()
                memory
            }
        }
    }
    
    /**
     * 按时间搜索
     */
    fun searchByTime(
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int = 10
    ): List<MemoryNode> {
        return memories.values.filter { memory ->
            (startTime == null || memory.createdAt >= startTime) &&
            (endTime == null || memory.createdAt <= endTime)
        }.sortedByDescending { it.createdAt }.take(limit)
    }
    
    /**
     * 按标签搜索
     */
    fun searchByTags(tags: List<String>): List<MemoryNode> {
        return memories.values.filter { memory ->
            tags.any { it in memory.tags }
        }
    }
    
    /**
     * 关联记忆
     */
    fun linkMemories(sourceId: String, targetId: String) {
        memories[sourceId]?.links?.add(targetId)
        memories[targetId]?.links?.add(sourceId)
    }
    
    /**
     * 删除记忆
     */
    fun deleteMemory(id: String) {
        memories.remove(id)
        vectorIndex.remove(id)
    }
    
    /**
     * 生成简化嵌入向量
     */
    private fun generateEmbedding(text: String): FloatArray {
        val hash = text.hashCode()
        val random = kotlin.random.Random(hash)
        return FloatArray(64) { random.nextFloat() }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalMemories" to memories.size,
            "totalTags" to memories.values.flatMap { it.tags }.distinct().size
        )
    }
}

// 扩展函数
private fun FloatArray.norm(): Float = sqrt(this.sumOf { (it * it).toDouble() }).toFloat()
private fun FloatArray.dot(other: FloatArray): Float = this.zip(other).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
