package com.potato.peacehaven.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.prompt.PromptBuilder;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.BotUserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户记忆自动提取器
 * <p>
 * 在 AI 回复后，调用 LLM 从对话中提取用户画像信息，
 * 经 ImportanceJudge 评分后存入 BotUserMemory.structuredMemories。
 * </p>
 * <p>
 * 写入流程：LLM 提取 → ImportanceJudge 评分 → 过滤低价值 → 构建 MemoryEntry → 存储
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryExtractor {

    private final LlmClient llmClient;
    private final AiProperties aiProps;
    private final UserMemoryService userMemoryService;
    private final ImportanceJudge importanceJudge;
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
            // 1. LLM 提取候选记忆
            String extractPrompt = buildExtractPrompt(senderNick, userMessage, aiReply, recentContext);
            List<LlmMessage> messages = List.of(
                    LlmMessage.system("你是一个信息提取助手。请从对话中提取关于用户的关键信息，以 JSON 格式输出。"),
                    LlmMessage.user(extractPrompt)
            );

            String response = llmClient.chat(messages, 0.3, 500);
            if (response == null || response.isBlank()) return;

            // 2. 解析 LLM 输出
            ExtractResult result = parseExtractResult(response);
            if (result == null || result.isEmpty()) {
                log.debug("[MemoryExtractor] 无有效候选 wxid={}", senderWxid);
                return;
            }

            // 3. 重要性评分 + 过滤 + 存储
            mergeAndUpdate(senderWxid, senderNick, result);

        } catch (Exception e) {
            log.warn("[MemoryExtractor] 提取失败 wxid={}: {}", senderWxid, e.getMessage());
        }
    }

    private String buildExtractPrompt(String senderNick, String userMessage,
                                       String aiReply, List<String> recentContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从以下对话中提取关于用户「").append(senderNick).append("」的有价值信息。\n\n");

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

        sb.append("\n请输出 JSON（只输出 JSON，不要其他内容）：\n");
        sb.append("{\n");
        sb.append("  \"candidates\": [\n");
        sb.append("    {\n");
        sb.append("      \"content\": \"提取的信息内容\",\n");
        sb.append("      \"type\": \"identity|preference|episode|relationship\",\n");
        sb.append("      \"importance\": 0.9,\n");
        sb.append("      \"confidence\": 0.85\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"summary_update\": \"一句话更新用户画像（可选，仅当有足够新信息时）\"\n");
        sb.append("}\n\n");
        sb.append("提取规则：\n");
        sb.append("- 每次最多提取 2 条最有价值的信息，宁缺毋滥\n");
        sb.append("- 只提取有长期价值的信息，日常闲聊（'今天好累'、'哈哈'、'666'）不要提取\n");
        sb.append("- **只提取用户本人主动陈述的信息**（'我是...'、'我有...'、'我喜欢...'），别人对他的调侃、起哄、外号一律不提取\n");
        sb.append("- 群聊中其他人说的话不要归到这个用户身上\n");
        sb.append("- **区分'关于本人'和'提到他人'**：用户在消息中提到别人的名字或昵称（如'XX是良子'、'XX好菜'），这是关于他人的信息，不要提取为该用户的 identity/preference。identity 只能描述用户自己的身份特征\n");
        sb.append("- **群聊调侃/串子/玩笑不是事实**：如'我是gay'、'不是男的'、'我是你爸'等群聊互怼内容，不要提取为身份信息\n");
        sb.append("- **游戏/社区术语不要过度解读**：如'5电'、'电喷'、'金条'等术语，如果不确定含义就降低 confidence 到 0.5 以下，或标记为 episode 而非 identity\n");
        sb.append("- **一次性交易/临时数字不要存**：如'我有6万金'、'98w成交'这类具体金额，变化很快，没有长期价值\n");
        sb.append("- **不要提取过于琐碎的行为**：如'电风扇坏了'、'浪费材料'这种随手一句话没有长期价值\n");
        sb.append("- type: identity=职业/身份/价值观, preference=兴趣偏好, episode=事件经历, relationship=人际关系\n");
        sb.append("- importance: 这条信息对未来回复的影响程度（0-1），0.9=改变身份, 0.5=具体事实, 0.3=临时状态\n");
        sb.append("- confidence: 你对这条信息准确性的把握（0-1）\n");
        sb.append("- 如果没有值得提取的信息，candidates 返回空数组\n");

        return sb.toString();
    }

    private ExtractResult parseExtractResult(String response) {
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```(?:json)?\\s*", "").replaceAll("```$", "").trim();
            }

            JsonNode root = objectMapper.readTree(json);

            // 解析 candidates[]
            List<MemoryCandidate> candidates = new ArrayList<>();
            JsonNode candidatesNode = root.get("candidates");
            if (candidatesNode != null && candidatesNode.isArray()) {
                for (JsonNode item : candidatesNode) {
                    String content = getTextOrNull(item, "content");
                    if (content == null || content.isBlank()) continue;

                    String type = getTextOrNull(item, "type");
                    if (type == null) type = "episode";

                    double importance = item.has("importance") ? item.get("importance").asDouble(0.5) : 0.5;
                    double confidence = item.has("confidence") ? item.get("confidence").asDouble(0.7) : 0.7;

                    candidates.add(new MemoryCandidate(content, type, importance, confidence));
                }
            }

            // 解析 summary_update
            String summaryUpdate = getTextOrNull(root, "summary_update");

            return new ExtractResult(candidates, summaryUpdate);
        } catch (Exception e) {
            log.debug("[MemoryExtractor] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String getTextOrNull(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull() && !node.get(field).asText().isBlank()) {
            return node.get(field).asText().trim();
        }
        return null;
    }

    private void mergeAndUpdate(String wxid, String nickname, ExtractResult result) {
        double threshold = aiProps.getPrompt().getMemoryImportanceThreshold();
        int maxEntries = aiProps.getPrompt().getMaxMemoryEntries();

        // 加载现有记忆
        Optional<BotUserMemory> existing = userMemoryService.getUserMemory(wxid);
        BotUserMemory memory = existing.orElse(BotUserMemory.builder().wxid(wxid).build());

        if (nickname != null) memory.setNickname(nickname);

        // 获取现有结构化记忆（可变列表）
        List<MemoryEntry> memories = new ArrayList<>(
                memory.getStructuredMemories() != null ? memory.getStructuredMemories() : List.of());

        int stored = 0;
        int discarded = 0;

        // 对每个候选记忆评分并存储
        for (MemoryCandidate candidate : result.candidates) {
            // 使用 ImportanceJudge 评分（可覆盖 LLM 给出的 importance）
            ImportanceJudge.ImportanceResult judgeResult =
                    importanceJudge.judge(candidate.content, candidate.type);

            // 加权融合：规则评分权重 0.6，LLM 评分权重 0.4（规则更保守可靠）
            double finalImportance = 0.6 * judgeResult.getImportance() + 0.4 * candidate.importance;
            double finalConfidence = 0.6 * judgeResult.getConfidence() + 0.4 * candidate.confidence;
            int ttlDays = judgeResult.getTtlDays();

            if (finalImportance < threshold || !judgeResult.isShouldStore()) {
                discarded++;
                log.debug("[MemoryExtractor] 丢弃低价值记忆 content='{}', importance={}",
                        candidate.content, String.format("%.2f", finalImportance));
                continue;
            }

            // 检查是否已存在相似记忆（内容去重）
            boolean duplicate = memories.stream()
                    .anyMatch(m -> !m.isExpired() && isSimilar(m.getContent(), candidate.content));
            if (duplicate) {
                discarded++;
                continue;
            }

            // 构建 MemoryEntry
            MemoryEntry entry = MemoryEntry.builder()
                    .type(candidate.type)
                    .content(candidate.content)
                    .importance(finalImportance)
                    .confidence(finalConfidence)
                    .ttlDays(ttlDays)
                    .source(candidate.content.length() > 30
                            ? candidate.content.substring(0, 30) + "..." : candidate.content)
                    .promptVersion(PromptBuilder.PROMPT_VERSION)
                    .build();

            memories.add(entry);
            stored++;
        }

        // 清理过期记忆
        int beforeClean = memories.size();
        memories.removeIf(MemoryEntry::isExpired);
        int cleaned = beforeClean - memories.size();

        // 限制总数量（manual 条目永远保留，auto 条目按 importance 截断）
        if (memories.size() > maxEntries) {
            List<MemoryEntry> manualEntries = memories.stream()
                    .filter(MemoryEntry::isManual)
                    .collect(Collectors.toList());
            List<MemoryEntry> autoEntries = memories.stream()
                    .filter(m -> !m.isManual())
                    .sorted((a, b) -> Double.compare(b.getImportance(), a.getImportance()))
                    .collect(Collectors.toList());

            int autoSlots = Math.max(0, maxEntries - manualEntries.size());
            memories = new ArrayList<>(manualEntries);
            memories.addAll(autoEntries.subList(0, Math.min(autoSlots, autoEntries.size())));
        }

        // 更新 summary
        if (result.summaryUpdate != null && !result.summaryUpdate.isBlank()) {
            memory.setSummary(result.summaryUpdate);
        }

        // 保存
        memory.setStructuredMemories(memories);
        userMemoryService.saveStructured(wxid, nickname, memories, result.summaryUpdate);

        log.info("[MemoryExtractor] 更新记忆 wxid={}, stored={}, discarded={}, cleaned={}, total={}",
                wxid, stored, discarded, cleaned, memories.size());
    }

    /**
     * 相似度检查：双向子串 + 字符重叠率
     * <p>
     * 解决"曾将暗恋对象制作成AI女友" vs "曾将crush转化为AI女友"
     * 这类措辞不同但语义相同的重复问题。
     * </p>
     */
    private boolean isSimilar(String existing, String candidate) {
        if (existing == null || candidate == null) return false;
        String a = existing.toLowerCase();
        String b = candidate.toLowerCase();

        // 双向子串包含
        if (a.contains(b) || b.contains(a)) return true;

        // 字符重叠率：较短字符串中有多少字符出现在较长字符串中
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() > b.length() ? a : b;

        if (shorter.length() < 4) return false;

        int matchCount = 0;
        boolean[] used = new boolean[longer.length()];
        for (char c : shorter.toCharArray()) {
            for (int i = 0; i < longer.length(); i++) {
                if (!used[i] && longer.charAt(i) == c) {
                    used[i] = true;
                    matchCount++;
                    break;
                }
            }
        }
        double overlapRatio = (double) matchCount / shorter.length();
        return overlapRatio > 0.7; // 70% 以上字符重叠视为重复
    }

    /**
     * LLM 提取候选记忆 DTO
     */
    private record MemoryCandidate(String content, String type, double importance, double confidence) {}

    /**
     * LLM 提取结果 DTO
     */
    private record ExtractResult(List<MemoryCandidate> candidates, String summaryUpdate) {
        boolean isEmpty() {
            return (candidates == null || candidates.isEmpty())
                    && (summaryUpdate == null || summaryUpdate.isBlank());
        }
    }
}
