package com.potato.peacehaven.ai.embedding;

import java.util.List;

/**
 * Embedding 服务接口
 * <p>将文本转换为向量表示，用于语义检索。</p>
 */
public interface EmbeddingService {

    /**
     * 将单条文本转换为向量
     *
     * @param text 输入文本
     * @return 向量数组，失败时返回 null
     */
    float[] embed(String text);

    /**
     * 批量将文本转换为向量
     *
     * @param texts 文本列表
     * @return 向量数组列表（顺序与输入一致），失败时返回 null
     */
    float[][] embedBatch(List<String> texts);

    /**
     * 获取向量维度
     */
    int getDimensions();
}
