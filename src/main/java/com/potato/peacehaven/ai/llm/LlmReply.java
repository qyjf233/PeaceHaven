package com.potato.peacehaven.ai.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 结构化回复 DTO
 * <p>
 * 当 PromptBuilder 开启 JSON 输出模式（jsonReplyFormat=true）时，
 * LLM 会输出包含 reply/confidence/memory_used 的 JSON。
 * 此 DTO 用于解析该 JSON，支持调试和效果评估。
 * </p>
 * <p>
 * 解析失败时，Pipeline 会 fallback 到原始文本模式。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmReply {

    /** 要发送的微信聊天内容 */
    private String reply;

    /** 回复符合本人风格的自信度（0-1） */
    private double confidence;

    /** 参考的记忆条目名称列表 */
    @Builder.Default
    private List<String> memoryUsed = List.of();

    /** 回复理由（内部调试，不发送） */
    private String replyReason;

    /** 是否需要更新记忆（内部决策参考） */
    private boolean shouldUpdateMemory;

    /**
     * 从原始 LLM 文本中解析 LlmReply
     * <p>
     * 支持以下格式：
     * <ul>
     *   <li>纯 JSON: {"reply": "xxx", "confidence": 0.85, "memory_used": []}</li>
     *   <li>Markdown 代码块包裹: ```json ... ```</li>
     * </ul>
     * </p>
     *
     * @param rawText LLM 原始输出
     * @return 解析结果，失败时返回 null
     */
    public static LlmReply parse(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;

        String json = rawText.trim();

        // 去除 markdown 代码块包裹
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            json = json.trim();
        }

        // 必须是 JSON 对象格式
        if (!json.startsWith("{") || !json.endsWith("}")) return null;

        try {
            return parseJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 轻量 JSON 解析（不引入额外依赖）
     * <p>
     * 只解析 reply(String)、confidence(number)、memory_used(array of strings) 三个字段。
     * </p>
     */
    private static LlmReply parseJson(String json) {
        String reply = extractStringField(json, "reply");
        if (reply == null) return null;

        double confidence = 0;
        try {
            String confStr = extractRawField(json, "confidence");
            if (confStr != null) confidence = Double.parseDouble(confStr.trim());
        } catch (NumberFormatException ignored) {}

        List<String> memoryUsed = extractStringArray(json, "memory_used");

        // 解析 replyReason
        String replyReason = extractStringField(json, "reply_reason");

        // 解析 should_update_memory
        boolean shouldUpdateMemory = false;
        String updateMemStr = extractRawField(json, "should_update_memory");
        if (updateMemStr != null) {
            shouldUpdateMemory = "true".equalsIgnoreCase(updateMemStr.trim());
        }

        return LlmReply.builder()
                .reply(reply)
                .confidence(confidence)
                .memoryUsed(memoryUsed)
                .replyReason(replyReason)
                .shouldUpdateMemory(shouldUpdateMemory)
                .build();
    }

    private static String extractStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return null;

        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;

        // 处理转义字符
        StringBuilder sb = new StringBuilder();
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"' || next == '\\' || next == 'n' || next == 't') {
                    sb.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                    i++;
                    continue;
                }
            }
            if (c == '"') break;
            sb.append(c);
        }

        return sb.toString();
    }

    private static String extractRawField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return null;

        // 跳过空白找到值
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;

        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;

        return json.substring(start, end).trim();
    }

    private static List<String> extractStringArray(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return List.of();

        int openBracket = json.indexOf('[', idx + key.length());
        if (openBracket < 0) return List.of();

        int closeBracket = json.indexOf(']', openBracket);
        if (closeBracket < 0) return List.of();

        String arrayContent = json.substring(openBracket + 1, closeBracket).trim();
        if (arrayContent.isEmpty()) return List.of();

        List<String> result = new java.util.ArrayList<>();
        // 提取引号内的字符串
        int pos = 0;
        while (pos < arrayContent.length()) {
            int quoteStart = arrayContent.indexOf('"', pos);
            if (quoteStart < 0) break;
            int quoteEnd = arrayContent.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) break;
            result.add(arrayContent.substring(quoteStart + 1, quoteEnd));
            pos = quoteEnd + 1;
        }

        return result;
    }
}
