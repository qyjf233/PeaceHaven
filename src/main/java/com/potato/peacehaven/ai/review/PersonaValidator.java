package com.potato.peacehaven.ai.review;

import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 人格漂移检测器
 * <p>
 * 在回复发送前进行轻量规则校验，防止 AI 回复偏离数字分身人格。
 * 不额外调用 LLM（零成本），纯规则检测。
 * </p>
 * <p>
 * 检测维度：
 * <ol>
 *   <li>长度异常 —— 微信消息不应过长（真人不会发长文）</li>
 *   <li>格式异常 —— 不应包含 Markdown 标记</li>
 *   <li>AI 特征 —— 不应出现典型 AI 用语</li>
 *   <li>人格越界 —— 不应暴露 prompt / memory / 系统规则</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaValidator {

    private final AiProperties aiProps;

    /** 最大合理长度（微信单条消息通常不超过 100 字） */
    private static final int MAX_REASONABLE_LENGTH = 150;

    /** Markdown 标记模式 */
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile(
            "(?m)^#{1,6}\\s|" +       // 标题 #
            "\\*\\*[^*]+\\*\\*|" +    // 加粗 **text**
            "```|\\[.*]\\(.*\\)|" +    // 代码块 / 链接
            "^\\s*[-*]\\s+"           // 列表
    );

    /** AI 特征用语 */
    private static final List<String> AI_PHRASES = List.of(
            "作为AI", "作为一个人工智能", "根据我的分析", "根据我的训练",
            "首先.*其次.*最后", "让我来.*解答", "我来.*帮您",
            "请注意.*以下几点", "以下是我的.*建议",
            "我是一个.*助手", "我是.*模型",
            "对不起.*我无法", "抱歉.*我不能"
    );

    private static final Pattern AI_PATTERN = Pattern.compile(
            String.join("|", AI_PHRASES)
    );

    /** 系统信息泄露关键词 */
    private static final Set<String> SYSTEM_LEAK_KEYWORDS = Set.of(
            "system prompt", "system message", "记忆系统", "记忆条目",
            "prompt版本", "persona", "数字分身", "记忆提取",
            "structuredmemories", "memory rag", "importance评分"
    );

    /**
     * 校验回复是否符合人格
     *
     * @param reply AI 生成的回复
     * @return 校验结果
     */
    public ValidationResult validate(String reply) {
        if (reply == null || reply.isBlank()) {
            return new ValidationResult(false, "回复为空", null);
        }

        String trimmed = reply.trim();

        // 1. 长度检查
        if (trimmed.length() > MAX_REASONABLE_LENGTH) {
            log.info("[PersonaValidator] 长度异常: {} 字", trimmed.length());
            // 尝试截取到合理长度（到句号或换行处）
            String cleaned = truncateToReasonable(trimmed);
            return new ValidationResult(false,
                    "回复过长(" + trimmed.length() + "字)，已截取", cleaned);
        }

        // 2. Markdown 格式检查
        if (MARKDOWN_PATTERN.matcher(trimmed).find()) {
            log.info("[PersonaValidator] 检测到 Markdown 标记");
            String cleaned = strippedMarkdown(trimmed);
            return new ValidationResult(false, "包含 Markdown 标记", cleaned);
        }

        // 3. AI 特征检查
        if (AI_PATTERN.matcher(trimmed).find()) {
            log.info("[PersonaValidator] 检测到 AI 特征用语");
            return new ValidationResult(false, "包含 AI 特征用语", null);
        }

        // 4. 系统信息泄露检查
        String lower = trimmed.toLowerCase();
        for (String keyword : SYSTEM_LEAK_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) {
                log.info("[PersonaValidator] 检测到系统信息泄露: {}", keyword);
                return new ValidationResult(false, "泄露系统信息: " + keyword, null);
            }
        }

        // 通过所有检查
        return new ValidationResult(true, "通过", trimmed);
    }

    /**
     * 截取到合理长度（尽量在句号或换行处断开）
     */
    private String truncateToReasonable(String text) {
        if (text.length() <= MAX_REASONABLE_LENGTH) return text;

        // 在前 MAX_REASONABLE_LENGTH 个字符中找最后一个句号或换行
        String prefix = text.substring(0, MAX_REASONABLE_LENGTH);
        int lastBreak = Math.max(prefix.lastIndexOf('。'), prefix.lastIndexOf('\n'));
        if (lastBreak > MAX_REASONABLE_LENGTH / 2) {
            return prefix.substring(0, lastBreak + 1);
        }

        // 找不到断点，直接截断
        return prefix + "…";
    }

    /**
     * 去除 Markdown 标记
     */
    private String strippedMarkdown(String text) {
        return text
                .replaceAll("```[\\s\\S]*?```", "")  // 代码块
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")  // 加粗
                .replaceAll("^#{1,6}\\s*", "")        // 标题
                .replaceAll("^\\s*[-*]\\s+", "")      // 列表
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1") // 链接
                .trim();
    }

    /**
     * 校验结果
     *
     * @param valid       是否通过
     * @param reason      原因（日志用）
     * @param cleanedReply 清洗后的回复（可为 null，表示应拒绝）
     */
    public record ValidationResult(boolean valid, String reason, String cleanedReply) {}
}
