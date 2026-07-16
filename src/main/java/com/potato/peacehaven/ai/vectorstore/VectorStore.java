package com.potato.peacehaven.ai.vectorstore;

import java.util.List;
import java.util.Map;

/**
 * 向量存储接口
 * <p>
 * 抽象向量数据库操作，支持多种后端实现：
 * <ul>
 *   <li>InMemoryVectorStore - 内存实现（适合万级数据量，快速起步）</li>
 *   <li>PGVectorStore - PostgreSQL + pgvector 扩展（生产推荐）</li>
 *   <li>MilvusVectorStore - Milvus 向量数据库</li>
 *   <li>QdrantVectorStore - Qdrant 向量数据库</li>
 * </ul>
 * </p>
 */
public interface VectorStore {

    /**
     * 插入或更新单个文档
     *
     * @param id       文档唯一标识
     * @param vector   向量
     * @param metadata 元数据
     */
    void upsert(String id, float[] vector, Map<String, String> metadata);

    /**
     * 批量插入或更新文档
     *
     * @param documents 文档列表
     */
    void upsertBatch(List<VectorDocument> documents);

    /**
     * 向量相似度搜索
     *
     * @param query   查询向量
     * @param topK    返回结果数量
     * @param filters 元数据过滤条件（key-value 精确匹配），null 表示不过滤
     * @return 按相似度降序排列的结果列表
     */
    List<VectorSearchResult> search(float[] query, int topK, Map<String, String> filters);

    /**
     * 删除单个文档
     *
     * @param id 文档 ID
     */
    void delete(String id);

    /**
     * 按元数据条件批量删除
     *
     * @param key   元数据 key
     * @param value 元数据 value
     */
    void deleteByMetadata(String key, String value);

    /**
     * 获取存储的文档总数
     */
    long count();
}
