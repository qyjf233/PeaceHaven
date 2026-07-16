package com.potato.peacehaven.ai.review;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 复合审核服务：空值检查 → PersonaValidator 人格漂移检测
 * <p>
 * 链式调用：
 * <ol>
 *   <li>空值检查（原有逻辑）</li>
 *   <li>PersonaValidator（人格漂移检测：长度 / Markdown / AI 特征 / 系统泄露）</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassThroughReviewService implements ReplyReviewService {

    private final PersonaValidator personaValidator;

    @Override
    public ReviewResult review(String originalMessage, String aiReply) {
        // 1. 空值检查
        if (aiReply == null || aiReply.isBlank()) {
            return ReviewResult.reject("AI 回复为空");
        }

        // 2. PersonaValidator 人格漂移检测
        PersonaValidator.ValidationResult validation = personaValidator.validate(aiReply);
        if (!validation.valid()) {
            // 如果有清洗后的回复，使用清洗版
            if (validation.cleanedReply() != null && !validation.cleanedReply().isBlank()) {
                log.info("[Review] PersonaValidator 修正: reason='{}', cleaned='{}'",
                        validation.reason(),
                        validation.cleanedReply().length() > 50
                                ? validation.cleanedReply().substring(0, 50) + "..."
                                : validation.cleanedReply());
                return ReviewResult.pass(validation.cleanedReply());
            }
            // 无法修正，拒绝
            log.info("[Review] PersonaValidator 拒绝: reason='{}'", validation.reason());
            return ReviewResult.reject(validation.reason());
        }

        return ReviewResult.pass(validation.cleanedReply() != null ? validation.cleanedReply() : aiReply);
    }
}
