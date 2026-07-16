package com.potato.peacehaven.ai.vectorstore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量搜索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorSearchResult {

    /** 匹配的文档 ID */
    private String id;

    /** 相似度分数（0-1，越高越相似） */
    private double score;

    /** 元数据 */
    private Map<String, String> metadata;
}
