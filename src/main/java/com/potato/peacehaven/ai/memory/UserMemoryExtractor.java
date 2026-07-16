package com.potato.peacehaven.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.BotUserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户记忆自动提取器
 * <p>
 * 在 AI 回复后，调用 LLM 从对话中提取用户画像信息（摘要、标签、事实），
 * 增量更新到 bot_user_memory 表，实现长期记忆积累。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryExtractor {

    private final LlmClient llmClient;
    private final AiProperties aiProps;
    private final UserMemoryService userMemoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从对话中提取用户画像并更新记忆
     *
     * @param senderWxid    发送者 wxid
     * @param senderNick    发送者昵称
     * @param userMessage   用户发送的消息
     * @param aiReply       AI 的回复（可选）
     * @param recentContext 最近几条对话上下文（可选）
     */
    public void extractAndUpdate(String senderWxid, String senderNick,
                                  String userMessage, String aiReply,
                                  List<String> recentContext) {
        if (!aiProps.isReady() || senderWxid == null || userMessage == null) return;

        try {
            // 构建提取 prompt
            String extractPrompt = buildExtractPrompt(senderNick, userMessage, aiReply, recentContext);

            List<LlmMessage> messages = List.of(
                    LlmMessage.system("你是一个信息提取助手。请从对话中提取关于用户的关键信息，以 JSON 格式输出。"),
                    LlmMessage.user(extractPrompt)
            );

            String response = llmClient.chat(messages, 0.3, 500); // 低温度，确保稳定输出
            if (response == null || response.isBlank()) return;

            // 解析 LLM 返回的 JSON
            MemoryExtractResult result = parseExtractResult(response);
            if (result == null || result.isEmpty()) return;

            // 合并到现有记忆
            mergeAndUpdate(senderWxid, senderNick, result);

        } catch (Exception e) {
            log.warn("[MemoryExtractor] 提取失败 wxid={}: {}", senderWxid, e.getMessage());
        }
    }

    private String buildExtractPrompt(String senderNick, String userMessage,
                                       String aiReply, List<String> recentContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从以下对话中提取关于用户「").append(senderNick).append("」的信息。\n\n");

        if (recentContext != null && !recentContext.isEmpty()) {
            sb.append("【最近对话】\n");
            for (String ctx : recentContext) {
                sb.append(ctx).append("\n");
            }
            sb.append("\n");
        }

        sb.append("【当前对话】\n");
        sb.append(senderNick).append(": ").append(userMessage).append("\n");
        if (aiReply != null && !aiReply.isBlank()) {
            sb.append("我: ").append(aiReply).append("\n");
        }

        sb.append("\n请输出 JSON，格式如下（只输出 JSON，不要其他内容）：\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"一句话描述这个用户（可选，仅当有足够信息时）\",\n");
        sb.append("  \"tags\": [\"标签1\", \"标签2\"],\n");
        sb.append("  \"facts\": [\"关于用户的一个事实\", \"另一个事实\"]\n");
        sb.append("}\n\n");
        sb.append("提取规则：\n");
        sb.append("- 只提取对话中明确提及或可合理推断的信息\n");
        sb.append("- tags: 职业、兴趣、性格、地点等关键词（2-4个字）\n");
        sb.append("- facts: 具体的事实陈述（如\"养了一只猫叫小花\"）\n");
        sb.append("- 如果无法提取有效信息，返回空数组或 null\n");

        return sb.toString();
    }

    private MemoryExtractResult parseExtractResult(String response) {
        try {
            // 清理可能的 markdown 代码块
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```(?:json)?\\s*", "").replaceAll("```$", "").trim();
            }

            JsonNode root = objectMapper.readTree(json);

            String summary = null;
            if (root.has("summary") && !root.get("summary").isNull()) {
                summary = root.get("summary").asText();
            }

            List<String> tags = parseStringArray(root.get("tags"));
            List<String> facts = parseStringArray(root.get("facts"));

            return new MemoryExtractResult(summary, tags, facts);
        } catch (Exception e) {
            log.debug("[MemoryExtractor] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isNull() && !item.asText().isBlank()) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }

    private void mergeAndUpdate(String wxid, String nickname, MemoryExtractResult result) {
        Optional<BotUserMemory> existing = userMemoryService.getUserMemory(wxid);
        BotUserMemory memory = existing.orElse(BotUserMemory.builder().wxid(wxid).build());

        if (nickname != null) memory.setNickname(nickname);

        // Summary: 覆盖更新（取最新的完整描述）
        if (result.summary != null && !result.summary.isBlank()) {
            memory.setSummary(result.summary);
        }

        // Tags: 合并去重，最多保留 15 个
        if (!result.tags.isEmpty()) {
            List<String> merged = new ArrayList<>();
            if (memory.getTags() != null) merged.addAll(memory.getTags());
            for (String tag : result.tags) {
                if (!merged.contains(tag) && merged.size() < 15) {
                    merged.add(tag);
                }
            }
            memory.setTags(merged);
        }

        // Facts: 合并去重，最多保留 20 个
        if (!result.facts.isEmpty()) {
            List<String> merged = new ArrayList<>();
            if (memory.getFacts() != null) merged.addAll(memory.getFacts());
            for (String fact : result.facts) {
                if (!merged.contains(fact) && merged.size() < 20) {
                    merged.add(fact);
                }
            }
            memory.setFacts(merged);
        }

        userMemoryService.saveOrUpdate(wxid, nickname, memory.getSummary(), memory.getTags(), memory.getFacts());
        log.info("[MemoryExtractor] 更新画像 wxid={}, tags={}, facts={}",
                wxid, result.tags.size(), result.facts.size());
    }

    /**
     * LLM 提取结果 DTO
     */
    private record MemoryExtractResult(String summary, List<String> tags, List<String> facts) {
        boolean isEmpty() {
            return (summary == null || summary.isBlank())
                    && tags.isEmpty()
                    && facts.isEmpty();
        }
    }
}
