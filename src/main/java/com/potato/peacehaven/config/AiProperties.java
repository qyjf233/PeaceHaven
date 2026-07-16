package com.potato.peacehaven.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    /** Persona Engine 学习配置 */
    private LearningConfig learning = new LearningConfig();

    /**
     * 判断 AI 系统是否可用（总开关 + LLM 配置完整）
     */
    public boolean isReady() {
        return enabled
                && llm != null
                && llm.getApiKey() != null && !llm.getApiKey().isBlank()
                && llm.getBaseUrl() != null && !llm.getBaseUrl().isBlank();
    }

    /**
     * 获取当前场景的 temperature
     * <p>
     * 优先使用场景温度，否则回退到基础温度。
     * </p>
     *
     * @param scene 场景类型（normal/humor/question），可为 null
     * @return 适用的 temperature
     */
    public Double resolveTemperature(String scene) {
        if (scene != null && prompt.getSceneTemperature() != null) {
            Double sceneTemp = prompt.getSceneTemperature().get(scene);
            if (sceneTemp != null) {
                return sceneTemp;
            }
        }
        return llm.getTemperature();
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
        private int cooldownSeconds = 1;
        /** 无触发条件时随机回复概率（0-1，群聊） */
        private double randomRate = 0.15;
        /** true=仅被@时才回复 */
        private boolean onlyAt = false;
        /** 上下文拉取条数 */
        private int contextSize = 15;
        /** RAG 检索条数 */
        private int ragTopK = 8;

        /** 话题感知总开关 */
        private boolean topicAware = true;

        /** 话题持续次数阈值（超过则判定为过热） */
        private int topicStaleThreshold = 3;

        /** 是否启用对话摘要替代原始上下文 */
        private boolean useConversationSummary = true;

        /** 摘要缓存时间（秒），同一群聊在此时间内复用摘要 */
        private int summaryCacheSeconds = 300;

        /** 私聊专属配置 */
        private PrivateConfig privateChat = new PrivateConfig();
    }

    @Getter
    @Setter
    public static class PrivateConfig {
        /** 私聊随机回复概率（0-1，通常高于群聊） */
        private double randomRate = 1;
        /** 私聊最短回复间隔（秒，通常短于群聊） */
        private int cooldownSeconds = 1;
        /** 消息聚合等待时间（秒，等待对方发完再回复） */
        private int bufferSeconds = 5;
        /** 私聊提问回复概率（0-1，通常高于群聊） */
        private double questionRate = 1;
    }

    @Getter
    @Setter
    public static class PromptConfig {
        /** 扮演的用户名 */
        private String personaName;
        /** 额外提示词（追加到 system prompt 末尾） */
        private String customInstructions = "";
        /**
         * 说话风格描述（手动配置）
         * <p>
         * 配了之后直接使用这段描述指导 AI 回复风格，
         * 不再从 RAG 记录自动提炼（避免具体名词泄露）。
         * 不配则自动提炼。
         * </p>
         */
        private String styleDescription = "";

        /**
         * 反锚定提示（话题过热时注入到 Prompt 中）
         */
        private String antiAnchoringHint = "当前话题已经讨论较多。如果最新消息是新主题，请自然跟随新主题，不要主动回到旧话题。";

        /**
         * LLM 输出格式开关
         * <p>
         * true = 要求 LLM 输出结构化 JSON（含 reply/confidence/memory_used），用于调试和评估。
         * false = 纯文本输出（默认，生产模式）。
         * </p>
         */
        private boolean jsonReplyFormat = false;

        /** 记忆提取重要性阈值（低于此值丢弃） */
        private double memoryImportanceThreshold = 0.3;

        /** 记忆条目最大数量（每人） */
        private int maxMemoryEntries = 50;

        /** 人格维度配置（从 style-description 分离） */
        private PersonalityConfig personality;

        /** 场景 temperature 覆盖（如 humor=0.85, normal=0.7） */
        private Map<String, Double> sceneTemperature;
    }

    @Getter
    @Setter
    public static class PersonalityConfig {
        /** 幽默感等级：high / medium / low */
        private String humorLevel;
        /** 吐槽/调侃等级：high / medium / low */
        private String sarcasmLevel;
        /** 随意程度：high / medium / low */
        private String casualLevel;
        /** 温暖度：high / medium / low */
        private String warmthLevel;
    }

    /**
     * Persona Engine 学习配置
     */
    @Getter
    @Setter
    public static class LearningConfig {
        /** 是否启用定时学习 */
        private boolean enabled = false;
        /** 学习间隔（小时） */
        private int intervalHours = 6;
        /** 最低本人消息数才触发学习 */
        private int minSamples = 50;
        /** 学习窗口内最大消息数 */
        private int maxSamples = 200;
        /** Bootstrap 配置（冷启动保护） */
        private BootstrapConfig bootstrap = new BootstrapConfig();
    }

    /**
     * Bootstrap 冷启动保护配置
     * <p>前 N 条消息只采集不更新 persona，防止早期污染</p>
     */
    @Getter
    @Setter
    public static class BootstrapConfig {
        /** 是否启用 bootstrap */
        private boolean enabled = true;
        /** bootstrap 期间采集的消息数阈值 */
        private int samples = 500;
    }
}
