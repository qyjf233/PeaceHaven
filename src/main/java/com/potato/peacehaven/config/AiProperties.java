package com.potato.peacehaven.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 分身系统配置属性
 * <p>绑定 application.yaml 中 ai.* 配置</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 总开关 */
    private boolean enabled = false;

    /** LLM 配置 */
    private LlmConfig llm = new LlmConfig();

    /** Embedding 配置 */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /** 回复策略配置 */
    private ReplyConfig reply = new ReplyConfig();

    /** Prompt 配置 */
    private PromptConfig prompt = new PromptConfig();

    /**
     * 判断 AI 系统是否可用（总开关 + LLM 配置完整）
     */
    public boolean isReady() {
        return enabled
                && llm != null
                && llm.getApiKey() != null && !llm.getApiKey().isBlank()
                && llm.getBaseUrl() != null && !llm.getBaseUrl().isBlank();
    }

    @Getter
    @Setter
    public static class LlmConfig {
        /** 供应商标识（仅日志用） */
        private String provider = "openai";
        /** API Key */
        private String apiKey;
        /** API 基础地址（如 https://api.deepseek.com/v1） */
        private String baseUrl;
        /** 模型名称 */
        private String model = "deepseek-chat";
        /** 温度参数（0-2，越高越随机） */
        private Double temperature = 0.85;
        /** 最大输出 token 数 */
        private Integer maxTokens = 200;
    }

    @Getter
    @Setter
    public static class EmbeddingConfig {
        /** API Key（可复用 LLM key） */
        private String apiKey;
        /** API 基础地址 */
        private String baseUrl;
        /** Embedding 模型名称 */
        private String model = "text-embedding-3-small";
        /** 向量维度 */
        private Integer dimensions = 1536;
    }

    @Getter
    @Setter
    public static class ReplyConfig {
        /** 每日回复上限 */
        private int maxPerDay = 50;
        /** 同群最短回复间隔（秒） */
        private int cooldownSeconds = 30;
        /** 无触发条件时随机回复概率（0-1） */
        private double randomRate = 0.15;
        /** true=仅被@时才回复 */
        private boolean onlyAt = false;
        /** 上下文拉取条数 */
        private int contextSize = 15;
        /** RAG 检索条数 */
        private int ragTopK = 8;
    }

    @Getter
    @Setter
    public static class PromptConfig {
        /** 扮演的用户名 */
        private String personaName;
        /** 额外提示词（追加到 system prompt 末尾） */
        private String customInstructions = "";
    }
}
