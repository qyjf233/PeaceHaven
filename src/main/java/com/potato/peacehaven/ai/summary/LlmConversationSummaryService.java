package com.potato.peacehaven.ai.summary;

import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService.ContextMessage;
import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Rule + LLM 混合对话摘要服务
 * <p>
 * 策略：
 * <ol>
 *   <li>Rule 层：先分析最近消息，判断是否有实质话题</li>
 *   <li>如果全是噪音（哈哈/666/牛等）→ 直接返回规则摘要，不调 LLM</li>
 *   <li>如果实质消息比例不足 → 返回简单规则摘要，不调 LLM</li>
 *   <li>只有出现实质话题时 → 才调用 LLM 生成摘要</li>
 * </ol>
 * 目标：一天只调用 ~10 次 Summary LLM，而不是几百次。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmConversationSummaryService implements ConversationSummaryService {

    private final LlmClient llmClient;
    private final AiProperties aiProps;

    /** chatroomId -> 缓存条目 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 噪音消息精确匹配集合 */
    private static final java.util.Set<String> NOISE_EXACT = java.util.Set.of(
            "哈哈", "哈哈哈", "哈哈哈哈", "呵呵", "嗯", "嗯嗯", "哦", "哦哦",
            "好的", "好", "行", "可以", "收到", "明白", "了解",
            "6", "666", "6666", "牛", "牛逼", "厉害了", "笑死", "绝了",
            "确实", "是的", "不是", "没有", "有", "对", "对的",
            "啊", "噢", "嗯嗯嗯", "xswl", "yyds", "awsl", "nb",
            "我去", "卧槽", "woc", "GG", "gg", "谢谢", "感谢",
            "好的好的", "行行行", "okok", "OK", "绷不住了",
            "真绷不住", "真的假的", "好吧", "行吧", "得了吧"
    );

    /** 疑问词（出现则视为实质消息） */
    private static final java.util.regex.Pattern QUESTION_PATTERN = java.util.regex.Pattern.compile(
            "怎么|为什么|如何|什么|哪[里个]|是不是|能不能|可以吗|有没有|[?？]|吗$|呢$"
    );

    /** 实质消息最低比例（低于此比例不调 LLM） */
    private static final double SUBSTANTIVE_RATIO_THRESHOLD = 0.3;

    /** 实质消息最低条数（低于此条数不调 LLM） */
    private static final int MIN_SUBSTANTIVE_COUNT = 2;

    @Override
    public String summarize(String chatroomId, List<ContextMessage> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return "";
        }

        // 1. 检查缓存
        String cached = getCached(chatroomId);
        if (cached != null) {
            log.debug("[Summary] 使用缓存摘要 chatroom={}", chatroomId);
            return cached;
        }

        // 2. Rule 层：分析消息质量
        List<ContextMessage> validMessages = recentMessages.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .collect(Collectors.toList());

        if (validMessages.isEmpty()) return "";

        int total = validMessages.size();
        int substantiveCount = 0;
        for (ContextMessage m : validMessages) {
            if (isSubstantive(m.getContent())) {
                substantiveCount++;
            }
        }

        double ratio = (double) substantiveCount / total;
        log.info("[Summary] Rule 分析 chatroom={}, total={}, substantive={}, ratio={}",
                chatroomId, total, substantiveCount, String.format("%.2f", ratio));

        // 3. Rule 判断：全是噪音或实质内容不足 -> 规则摘要（不调 LLM）
        if (substantiveCount == 0) {
            String ruleSummary = "最近大家在闲聊，没有明确主题。";
            putCache(chatroomId, ruleSummary);
            log.info("[Summary] Rule: 无实质内容 -> '{}'", ruleSummary);
            return ruleSummary;
        }

        if (substantiveCount < MIN_SUBSTANTIVE_COUNT || ratio < SUBSTANTIVE_RATIO_THRESHOLD) {
            // 实质消息太少，用规则拼最后几条
            String ruleSummary = buildRuleSummary(validMessages, substantiveCount);
            putCache(chatroomId, ruleSummary);
            log.info("[Summary] Rule: 实质内容不足 -> '{}'", ruleSummary);
            return ruleSummary;
        }

        // 4. 实质内容充足 -> 调用 LLM 生成摘要
        String chatText = validMessages.stream()
                .filter(m -> isSubstantive(m.getContent()) || m.getContent().length() > 2)
                .map(m -> {
                    String prefix = m.isSelf() ? "我" : m.getSenderNick();
                    return prefix + ": " + m.getContent();
                })
                .collect(Collectors.joining("\n"));

        if (chatText.isBlank()) return "";

        try {
            String summary = callLlm(chatText);
            if (summary != null && !summary.isBlank()) {
                putCache(chatroomId, summary);
                log.info("[Summary] LLM 摘要 chatroom={}, summary={}",
                        chatroomId,
                        summary.length() > 80 ? summary.substring(0, 80) + "..." : summary);
                return summary;
            }
        } catch (Exception e) {
            log.warn("[Summary] LLM 摘要生成失败 chatroom={}: {}", chatroomId, e.getMessage());
        }

        // 5. Fallback: 规则摘要
        String fallback = buildRuleSummary(validMessages, substantiveCount);
        putCache(chatroomId, fallback);
        log.info("[Summary] Fallback -> '{}'", fallback);
        return fallback;
    }

    /**
     * 判断消息是否有实质内容（非噪音）
     */
    private boolean isSubstantive(String content) {
        if (content == null || content.isBlank()) return false;
        String trimmed = content.trim();

        // 精确匹配噪音
        String normalized = trimmed.toLowerCase().replaceAll("[\\s!！。.]+", "");
        if (NOISE_EXACT.contains(normalized)) return false;

        // 包含疑问词 -> 实质
        if (QUESTION_PATTERN.matcher(trimmed).find()) return true;

        // 长度 > 4 字 -> 实质
        if (trimmed.length() > 4) return true;

        // 包含英文单词（可能是技术名词）-> 实质
        if (trimmed.matches(".*[a-zA-Z]{3,}.*")) return true;

        return false;
    }

    /**
     * 规则摘要：提取实质消息拼接，不调 LLM
     */
    private String buildRuleSummary(List<ContextMessage> messages, int substantiveCount) {
        List<ContextMessage> substantive = messages.stream()
                .filter(m -> isSubstantive(m.getContent()))
                .collect(Collectors.toList());

        if (substantive.isEmpty()) {
            return "最近大家在闲聊，没有明确主题。";
        }

        // 取最后 3 条实质消息
        int start = Math.max(0, substantive.size() - 3);
        String snippets = substantive.subList(start, substantive.size()).stream()
                .map(m -> {
                    String prefix = m.isSelf() ? "我" : m.getSenderNick();
                    return prefix + ": " + m.getContent();
                })
                .collect(Collectors.joining("; "));

        return "最近聊到了：" + snippets;
    }

    /**
     * 调用 LLM 生成摘要
     */
    private String callLlm(String chatText) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system("你是一个聊天摘要助手。请将以下群聊记录压缩为 2-3 句简短摘要。"),
                LlmMessage.user("请用 2-3 句话总结以下群聊内容，重点关注：\n"
                        + "1. 大家之前在聊什么话题\n"
                        + "2. 目前聊天正在转向什么新话题（如果有）\n"
                        + "3. 最后一条消息在说什么\n\n"
                        + "要求：简洁、客观、不添加主观评价。只输出摘要文本，不要加标题或前缀。\n\n"
                        + "聊天记录：\n" + chatText)
        );

        // 低温度确保稳定输出，限制输出长度
        String result = llmClient.chat(messages, 0.2, 150);
        return result != null ? result.trim() : null;
    }

    // ===== 缓存管理 =====

    private String getCached(String chatroomId) {
        if (chatroomId == null) return null;
        CacheEntry entry = cache.get(chatroomId);
        if (entry == null) return null;

        long cacheTtlMs = aiProps.getReply().getSummaryCacheSeconds() * 1000L;
        if (System.currentTimeMillis() - entry.timestamp > cacheTtlMs) {
            cache.remove(chatroomId);
            return null;
        }
        return entry.summary;
    }

    private void putCache(String chatroomId, String summary) {
        if (chatroomId == null) return;
        cache.put(chatroomId, new CacheEntry(summary, System.currentTimeMillis()));
    }

    private record CacheEntry(String summary, long timestamp) {}
}
