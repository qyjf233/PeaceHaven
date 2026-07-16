package com.potato.peacehaven.ai.topic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于规则的 RAG 检索判断
 * <p>
 * 策略：
 * - 短消息（<=4字）且无疑问词 -> 不查 RAG
 * - 包含疑问词 -> 查 RAG（可能在提问）
 * - 消息较长（>4字） -> 查 RAG（有实质内容）
 * - 纯表情/系统通知 -> 不查
 * </p>
 */
@Slf4j
@Component
public class SimpleTopicJudgeService implements TopicJudgeService {

    /** 纯语气/无意义消息 */
    private static final Set<String> SKIP_EXACT = Set.of(
            "哈哈", "哈哈哈", "哈哈哈哈", "呵呵", "嗯", "嗯嗯", "哦", "哦哦",
            "好的", "好", "行", "可以", "收到", "明白", "了解",
            "6", "666", "6666", "牛", "牛逼", "厉害了", "笑死", "绝了",
            "确实", "是的", "不是", "没有", "有", "对", "对的",
            "啊", "噢", "嗯嗯嗯", "xswl", "yyds", "awsl", "nb",
            "我去", "卧槽", "woc", "GG", "gg", "谢谢", "感谢",
            "好的好的", "行行行", "okok", "OK"
    );

    /** 疑问词检测 */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "怎么|为什么|如何|什么|哪[里个]|是不是|能不能|可以吗|有没有|[?？]|吗$|呢$|吧[?？]?|谁|几[点时个]|多少"
    );

    @Override
    public boolean needsRagLookup(String content, String chatroomId) {
        if (content == null || content.isBlank()) {
            log.debug("[TopicJudge] 空消息 -> 跳过 RAG");
            return false;
        }

        String trimmed = content.trim();

        // 1. 精确匹配噪音消息
        String normalized = trimmed.toLowerCase().replaceAll("[\\s!！。.]+", "");
        if (SKIP_EXACT.contains(normalized)) {
            log.debug("[TopicJudge] 噪音消息跳过: '{}'", trimmed);
            return false;
        }

        // 2. 包含疑问词 -> 查 RAG
        if (QUESTION_PATTERN.matcher(trimmed).find()) {
            log.debug("[TopicJudge] 检测到提问 -> 查 RAG: '{}'", 
                    trimmed.length() > 30 ? trimmed.substring(0, 30) + "..." : trimmed);
            return true;
        }

        // 3. 消息长度 > 4 字 -> 查 RAG（有实质内容）
        if (trimmed.length() > 4) {
            log.debug("[TopicJudge] 消息较长({}字) -> 查 RAG", trimmed.length());
            return true;
        }

        // 4. 短消息无疑问词 -> 不查
        log.debug("[TopicJudge] 短消息无疑问词 -> 跳过 RAG: '{}'", trimmed);
        return false;
    }
}
