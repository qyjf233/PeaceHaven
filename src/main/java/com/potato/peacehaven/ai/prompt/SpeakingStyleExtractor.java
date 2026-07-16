package com.potato.peacehaven.ai.prompt;

import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService.RetrievedRecord;
import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 说话风格提炼器
 * <p>
 * 解决问题：直接将 RAG 原文喂给 LLM 时，模型会照搬其中的具体名词（如薯条、黄瓜等），
 * 即使 prompt 明确说"不要照搬"也无效，因为 few-shot 学习中模型会直接模仿示例内容。
 * </p>
 * <p>
 * 解决方案：先调用 LLM 从 RAG 记录中**提炼风格描述**（语气、句式、口头禅），
 * 然后用这份不含具体话题的风格描述来指导回复，从根源上杜绝名词照搬。
 * </p>
 * <p>
 * 带缓存机制：风格描述 30 分钟内复用，避免每次回复都额外调 LLM。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeakingStyleExtractor {

    private final LlmClient llmClient;
    private final AiProperties aiProps;

    /** 缓存的风格描述 */
    private volatile String cachedStyle;
    /** 缓存时间戳 */
    private volatile long cachedAt;
    /** 缓存有效期（毫秒）：30 分钟 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    /**
     * 获取风格描述（优先用缓存，过期则重新提炼）
     *
     * @param ragRecords RAG 检索的本人历史记录
     * @return 风格描述文本，如果提炼失败则返回原始 RAG 文本（兜底）
     */
    public String getStyleDescription(List<RetrievedRecord> ragRecords) {
        if (ragRecords == null || ragRecords.isEmpty()) return "";

        // 检查缓存是否有效
        long now = System.currentTimeMillis();
        if (cachedStyle != null && (now - cachedAt) < CACHE_TTL_MS) {
            log.debug("[StyleExtractor] 使用缓存风格描述");
            return cachedStyle;
        }

        // 提炼新风格
        String rawText = formatRecords(ragRecords);
        if (rawText.isBlank()) return "";

        try {
            String style = extractStyle(rawText);
            if (style != null && !style.isBlank()) {
                cachedStyle = style;
                cachedAt = now;
                log.info("[StyleExtractor] 风格提炼成功: {}",
                        style.length() > 100 ? style.substring(0, 100) + "..." : style);
                return style;
            }
        } catch (Exception e) {
            log.warn("[StyleExtractor] 风格提炼失败，使用原文兜底: {}", e.getMessage());
        }

        // 兜底：返回原始文本（至少 RAG 还能工作）
        return rawText;
    }

    /**
     * 调用 LLM 提炼风格描述
     */
    private String extractStyle(String rawRecords) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system("你是一个语言风格分析专家。请分析以下聊天记录，提炼出说话人的风格特征。"),
                LlmMessage.user("请分析以下聊天记录中说话人的风格，输出一段简洁的风格描述（100字以内）。\n\n"
                        + "要求：\n"
                        + "- 只描述语气、句式长度、口头禅、表达习惯等风格特征\n"
                        + "- 绝对不要提及任何具体话题、名词、人名、食物、地点\n"
                        + "- 输出纯描述文本，不要加标题或前缀\n\n"
                        + "聊天记录：\n" + rawRecords)
        );

        // 低温度确保稳定输出，低 maxTokens 限制输出长度
        String result = llmClient.chat(messages, 0.2, 150);
        return result != null ? result.trim() : null;
    }

    /**
     * 格式化 RAG 记录为文本
     */
    private String formatRecords(List<RetrievedRecord> records) {
        return records.stream()
                .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                .map(r -> {
                    String nick = r.getSenderNick() != null ? r.getSenderNick() : "我";
                    return nick + ": " + r.getContent();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 手动清除缓存（可在配置变更时调用）
     */
    public void invalidateCache() {
        cachedStyle = null;
        cachedAt = 0;
        log.info("[StyleExtractor] 缓存已清除");
    }
}
