package com.potato.peacehaven.ai.embedding;

import com.potato.peacehaven.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * OpenAI 兼容格式的 Embedding 服务实现
 * <p>
 * 调用端点：{baseUrl}/embeddings
 * 支持 OpenAI / DeepSeek / 通义千问等所有兼容格式。
 * </p>
 */
@Slf4j
@Component
public class OpenAiEmbeddingService implements EmbeddingService {

    private final AiProperties aiProps;
    private final RestClient restClient;

    public OpenAiEmbeddingService(AiProperties aiProps) {
        this.aiProps = aiProps;
        this.restClient = RestClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        float[][] result = embedBatch(List.of(text));
        return result != null && result.length > 0 ? result[0] : null;
    }

    /** API 单次最大批量数（通义千问等供应商限制为 10） */
    private static final int MAX_BATCH_SIZE = 10;

    @Override
    @SuppressWarnings("unchecked")
    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return new float[0][];

        // 分批调用：API 限制单次最多 10 条
        if (texts.size() > MAX_BATCH_SIZE) {
            return embedBatchChunked(texts);
        }

        return doEmbed(texts);
    }

    /**
     * 分批嵌入并合并结果
     */
    private float[][] embedBatchChunked(List<String> texts) {
        List<float[]> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> chunk = texts.subList(i, end);
            float[][] chunkResult = doEmbed(chunk);
            if (chunkResult == null) {
                log.error("[Embedding] 分批嵌入失败 chunk={}-{}/{}", i, end, texts.size());
                return null;
            }
            for (float[] v : chunkResult) {
                allVectors.add(v);
            }
        }
        float[][] result = new float[allVectors.size()][];
        for (int i = 0; i < allVectors.size(); i++) {
            result[i] = allVectors.get(i);
        }
        log.info("[Embedding] 分批嵌入完成 {}/{} 条", result.length, texts.size());
        return result;
    }

    /**
     * 执行单次 Embedding API 调用
     */
    @SuppressWarnings("unchecked")
    private float[][] doEmbed(List<String> texts) {

        AiProperties.EmbeddingConfig cfg = aiProps.getEmbedding();
        String apiKey = cfg.getApiKey();
        String baseUrl = cfg.getBaseUrl();

        // 回退到 LLM 配置
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = aiProps.getLlm().getApiKey();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = aiProps.getLlm().getBaseUrl();
        }
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            log.warn("[Embedding] API Key 或 BaseUrl 未配置");
            return null;
        }

        String url = baseUrl.replaceAll("/+$", "") + "/embeddings";

        // 构建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("input", texts);
        if (cfg.getDimensions() != null && cfg.getDimensions() > 0) {
            body.put("dimensions", cfg.getDimensions());
        }

        try {
            log.info("[Embedding] 请求 {} model={} texts={}", url, cfg.getModel(), texts.size());

            Map<String, Object> resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (resp == null || resp.get("data") == null) {
                log.error("[Embedding] 响应为空或无 data: {}", resp);
                return null;
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            float[][] vectors = new float[data.size()][];

            for (int i = 0; i < data.size(); i++) {
                Map<String, Object> item = data.get(i);
                List<Number> embedding = (List<Number>) item.get("embedding");
                if (embedding == null) {
                    log.warn("[Embedding] 第 {} 条无 embedding", i);
                    vectors[i] = new float[0];
                    continue;
                }
                vectors[i] = new float[embedding.size()];
                for (int j = 0; j < embedding.size(); j++) {
                    vectors[i][j] = embedding.get(j).floatValue();
                }
            }

            log.info("[Embedding] 成功生成 {} 个向量，维度={}", vectors.length,
                    vectors.length > 0 ? vectors[0].length : 0);
            return vectors;

        } catch (Exception e) {
            log.error("[Embedding] 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public int getDimensions() {
        return aiProps.getEmbedding().getDimensions();
    }
}
