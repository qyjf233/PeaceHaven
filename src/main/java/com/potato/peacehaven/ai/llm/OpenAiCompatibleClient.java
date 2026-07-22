package com.potato.peacehaven.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/**
 * OpenAI 兼容格式的 LLM 客户端实现
 * <p>
 * 支持 DeepSeek / 通义千问 / Moonshot / 智谱等所有兼容 OpenAI Chat Completions 接口的供应商。
 * 调用端点：{baseUrl}/chat/completions
 * </p>
 */
@Slf4j
@Component
public class OpenAiCompatibleClient implements LlmClient {

    private final AiProperties aiProps;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleClient(AiProperties aiProps) {
        this.aiProps = aiProps;
        this.restClient = RestClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String chat(List<LlmMessage> messages, Double temperature, Integer maxTokens) {
        if (!aiProps.isReady()) {
            log.warn("[LLM] AI 系统未就绪（未启用或 API Key/BaseUrl 为空）");
            return null;
        }

        AiProperties.LlmConfig cfg = aiProps.getLlm();
        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        double temp = temperature != null ? temperature : cfg.getTemperature();
        int tokens = maxTokens != null ? maxTokens : cfg.getMaxTokens();

        // 构建请求体
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("messages", messages);
        body.put("temperature", temp);
        body.put("max_tokens", tokens);
        body.put("stream", false);

        try {
            log.info("[LLM] 请求 {} model={} msgs={} temp={}", url, cfg.getModel(), messages.size(), temp);

            // DEBUG 级别记录完整请求报文
            if (log.isDebugEnabled()) {
                try {
                    String reqJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
                    log.debug("[LLM] >>> 完整请求:\n{}", reqJson);
                } catch (Exception e) {
                    log.debug("[LLM] 请求序列化失败: {}", e.getMessage());
                }
            }

            Map<String, Object> resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (resp == null || resp.get("choices") == null) {
                log.error("[LLM] 响应为空或无 choices: {}", resp);
                return null;
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices.isEmpty()) {
                log.warn("[LLM] choices 为空");
                return null;
            }

            Map<String, Object> first = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) first.get("message");
            if (message == null) {
                log.warn("[LLM] message 为空: {}", first);
                return null;
            }

            String content = (String) message.get("content");
            log.info("[LLM] 回复长度={} content={}", 
                    content != null ? content.length() : 0,
                    content != null && content.length() > 80 ? content.substring(0, 80) + "..." : content);

            // DEBUG 级别记录完整响应
            if (log.isDebugEnabled()) {
                log.debug("[LLM] <<< 完整响应:\n{}", content);
            }

            return content;

        } catch (Exception e) {
            log.error("[LLM] 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
