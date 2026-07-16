package com.potato.peacehaven.ai.vectorstore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量存储实现
 * <p>
 * 使用 ConcurrentHashMap 存储向量，余弦相似度计算。
 * 适合万级以下数据量，重启后数据丢失（可配合定时重建索引）。
 * 后续可无缝切换到 PGVector/Milvus/Qdrant。
 * </p>
 */
@Slf4j
@Component
public class InMemoryVectorStore implements VectorStore {

    private final ConcurrentHashMap<String, VectorDocument> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(String id, float[] vector, Map<String, String> metadata) {
        if (id == null || vector == null) return;
        store.put(id, VectorDocument.builder()
                .id(id)
                .vector(vector)
                .metadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>())
                .build());
    }

    @Override
    public void upsertBatch(List<VectorDocument> documents) {
        if (documents == null) return;
        for (VectorDocument doc : documents) {
            if (doc != null && doc.getId() != null && doc.getVector() != null) {
                store.put(doc.getId(), doc);
            }
        }
        log.debug("[VectorStore] 批量写入 {} 条，当前总数={}", documents.size(), store.size());
    }

    @Override
    public List<VectorSearchResult> search(float[] query, int topK, Map<String, String> filters) {
        if (query == null || query.length == 0 || topK <= 0) {
            return Collections.emptyList();
        }

        List<VectorSearchResult> results = new ArrayList<>();

        for (VectorDocument doc : store.values()) {
            // 元数据过滤
            if (filters != null && !filters.isEmpty()) {
                Map<String, String> docMeta = doc.getMetadata();
                if (docMeta == null) continue;
                boolean match = true;
                for (Map.Entry<String, String> filter : filters.entrySet()) {
                    String docVal = docMeta.get(filter.getKey());
                    if (!filter.getValue().equals(docVal)) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;
            }

            // 计算余弦相似度
            double similarity = cosineSimilarity(query, doc.getVector());
            if (similarity > 0) {
                results.add(VectorSearchResult.builder()
                        .id(doc.getId())
                        .score(similarity)
                        .metadata(doc.getMetadata())
                        .build());
            }
        }

        // 按分数降序排序，取 Top-K
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results.subList(0, Math.min(topK, results.size()));
    }

    @Override
    public void delete(String id) {
        if (id != null) {
            store.remove(id);
        }
    }

    @Override
    public void deleteByMetadata(String key, String value) {
        if (key == null || value == null) return;
        store.entrySet().removeIf(entry -> {
            Map<String, String> meta = entry.getValue().getMetadata();
            return meta != null && value.equals(meta.get(key));
        });
    }

    @Override
    public long count() {
        return store.size();
    }

    /**
     * 计算余弦相似度
     *
     * @return 相似度值（-1 到 1），越接近 1 越相似
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) return 0.0;

        return dotProduct / denominator;
    }
}
