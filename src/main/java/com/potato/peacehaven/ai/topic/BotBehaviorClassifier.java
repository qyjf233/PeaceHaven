package com.potato.peacehaven.ai.topic;

import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bot 回复行为分类器
 * <p>
 * 使用轻量 LLM 调用对 bot 回复进行行为分类（REFUSE/ACCEPT/JOKE 等），
 * 而非基于文本相似度。这样可以准确检测"文字不同但行为相同"的模式。
 * <p>
 * 例如：
 * <ul>
 *   <li>"不骂" → REFUSE</li>
 *   <li>"骂你干啥" → REFUSE</li>
 *   <li>"我又不傻" → REFUSE</li>
 *   <li>"算了" → REFUSE</li>
 * </ul>
 * 文字完全不同，但行为一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotBehaviorClassifier {

    private final LlmClient llmClient;

    private static final String SYSTEM_PROMPT = """
            你是一个对话行为分类器。
            给定一段对话上下文和 bot 的最新回复，判断 bot 回复的"行为类型"。

            可选类型（只输出一个英文单词）：
            - REFUSE：拒绝、推辞、不配合对方请求
            - ACCEPT：接受、配合、同意对方请求
            - JOKE：调侃、开玩笑、玩梗、戏弄对方
            - COUNTER：反问、反客为主、把问题抛回给对方
            - CHANGE_TOPIC：主动转移话题
            - END：结束当前话题、收尾
            - NEUTRAL：普通对话，无明显行为倾向（日常聊天、信息交流）

            规则：
            1. 只看 bot 回复的行为意图，不看文字是否相似
            2. 结合上下文理解——同一个回复在不同上下文可能是不同行为
            3. 只输出类型名称，不要解释
            """;

    /**
     * 对 bot 回复进行行为分类
     *
     * @param botReply   bot 回复内容
     * @param userMsg    触发回复的用户消息（上下文参考）
     * @param lastBotReply 上一条 bot 回复（上下文参考，可为 null）
     * @return 行为分类，失败时返回 NEUTRAL
     */
    public BotBehavior classify(String botReply, String userMsg, String lastBotReply) {
        if (botReply == null || botReply.isBlank()) return BotBehavior.NEUTRAL;

        try {
            StringBuilder userContent = new StringBuilder();
            if (userMsg != null && !userMsg.isBlank()) {
                userContent.append("用户: ").append(userMsg).append("\n");
            }
            if (lastBotReply != null && !lastBotReply.isBlank()) {
                userContent.append("bot上次: ").append(lastBotReply).append("\n");
            }
            userContent.append("bot本次: ").append(botReply);

            List<LlmMessage> messages = List.of(
                    LlmMessage.system(SYSTEM_PROMPT),
                    LlmMessage.user(userContent.toString())
            );

            // 低温度保证分类稳定，限制 token 数（只需输出一个单词）
            String result = llmClient.chat(messages, 0.1, 20);
            if (result != null && !result.isBlank()) {
                BotBehavior behavior = parseBehavior(result.trim());
                log.debug("[BehaviorClassifier] classify={} | user={}, bot={}, lastBot={}",
                        behavior,
                        truncate(userMsg, 20), truncate(botReply, 20), truncate(lastBotReply, 20));
                return behavior;
            }
        } catch (Exception e) {
            log.warn("[BehaviorClassifier] 分类失败: {}", e.getMessage());
        }

        return BotBehavior.NEUTRAL;
    }

    /**
     * 解析 LLM 返回的行为标签
     */
    private BotBehavior parseBehavior(String text) {
        // 取第一行、第一个单词（LLM 可能输出额外内容）
        String firstLine = text.split("[\\n\\r]")[0].trim().toUpperCase();
        // 去除可能的标点或前缀
        firstLine = firstLine.replaceAll("[^A-Z_]", "");

        try {
            return BotBehavior.valueOf(firstLine);
        } catch (IllegalArgumentException e) {
            // 尝试模糊匹配
            if (firstLine.contains("REFUSE")) return BotBehavior.REFUSE;
            if (firstLine.contains("ACCEPT")) return BotBehavior.ACCEPT;
            if (firstLine.contains("JOKE")) return BotBehavior.JOKE;
            if (firstLine.contains("COUNTER")) return BotBehavior.COUNTER;
            if (firstLine.contains("CHANGE") || firstLine.contains("TOPIC")) return BotBehavior.CHANGE_TOPIC;
            if (firstLine.contains("END")) return BotBehavior.END;
            if (firstLine.contains("NEUTRAL")) return BotBehavior.NEUTRAL;
            log.debug("[BehaviorClassifier] 无法解析: '{}', fallback NEUTRAL", text);
            return BotBehavior.NEUTRAL;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
