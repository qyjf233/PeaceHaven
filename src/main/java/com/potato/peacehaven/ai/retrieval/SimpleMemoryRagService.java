package com.potato.peacehaven.ai.retrieval;

import com.potato.peacehaven.ai.memory.MemoryEntry;
import com.potato.peacehaven.ai.memory.UserMemoryService;
import com.potato.peacehaven.entity.BotUserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于关键词匹配的记忆 RAG 实现
 * <p>
 * 从 BotUserMemory 中加载用户画像，根据当前消息关键词
 * 筛选相关 facts 和 tags，返回带类型标签的结构化记忆文本。
 * 不使用向量检索（记忆数据量小，关键词匹配足够高效）。
 * </p>
 * <p>
 * 记忆读取双轨制：
 * <ul>
 *   <li>structuredMemories 非空 → 新路径（过滤过期 + 按 importance 排序 + 按 type 分组）</li>
 *   <li>structuredMemories 为空 → fallback 到旧的 tags/facts 逻辑</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleMemoryRagService implements MemoryRagService {

    private final UserMemoryService userMemoryService;

    @Override
    public String retrieveRelevantMemory(String senderWxid, String currentMessage) {
        if (senderWxid == null || senderWxid.isBlank()) return "";

        Optional<BotUserMemory> memoryOpt = userMemoryService.getUserMemory(senderWxid);
        if (memoryOpt.isEmpty()) {
            log.debug("[MemoryRAG] 无用户画像 wxid={}", senderWxid);
            return "";
        }

        BotUserMemory memory = memoryOpt.get();

        // 双轨制：优先使用 structuredMemories，fallback 到旧的 tags/facts
        if (memory.getStructuredMemories() != null && !memory.getStructuredMemories().isEmpty()) {
            return retrieveFromStructured(senderWxid, currentMessage, memory);
        } else {
            return retrieveFromLegacy(senderWxid, currentMessage, memory);
        }
    }

    /**
     * 新路径：从 structuredMemories 读取
     */
    private String retrieveFromStructured(String senderWxid, String currentMessage, BotUserMemory memory) {
        // 过滤过期 + 按 importance 排序
        List<MemoryEntry> active = memory.getStructuredMemories().stream()
                .filter(e -> !e.isExpired())
                .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
                .collect(Collectors.toList());

        if (active.isEmpty()) {
            // 全部过期，fallback
            return retrieveFromLegacy(senderWxid, currentMessage, memory);
        }

        // 如果有当前消息，做关键词筛选
        List<MemoryEntry> relevant = active;
        if (currentMessage != null && !currentMessage.isBlank()) {
            String messageLower = currentMessage.toLowerCase();
            relevant = active.stream()
                    .filter(e -> isContentRelevant(e.getContent(), messageLower))
                    .collect(Collectors.toList());
        }

        // 如果关键词筛选后无结果，取 importance 最高的前 5 条
        if (relevant.isEmpty()) {
            relevant = active.stream().limit(5).collect(Collectors.toList());
        }

        // 按 type 分组输出
        String result = formatStructuredOutput(memory, relevant);
        log.debug("[MemoryRAG] structured wxid={}, active={}, relevant={}",
                senderWxid, active.size(), relevant.size());
        return result;
    }

    /**
     * 旧路径：从 tags/facts 读取（向后兼容）
     */
    private String retrieveFromLegacy(String senderWxid, String currentMessage, BotUserMemory memory) {
        // 如果消息为空，返回全部记忆
        if (currentMessage == null || currentMessage.isBlank()) {
            return formatLegacyMemory(memory, memory.getTags(), memory.getFacts());
        }

        // 关键词筛选相关 facts 和 tags
        String messageLower = currentMessage.toLowerCase();
        List<String> relevantTags = filterRelevant(memory.getTags(), messageLower);
        List<String> relevantFacts = filterRelevant(memory.getFacts(), messageLower);

        // 如果有匹配结果，返回结构化类型标签
        if (!relevantTags.isEmpty() || !relevantFacts.isEmpty()) {
            String result = formatLegacyMemory(memory, relevantTags, relevantFacts);
            log.debug("[MemoryRAG] legacy wxid={}, tags={}, facts={}",
                    senderWxid, relevantTags.size(), relevantFacts.size());
            return result;
        }

        // 无匹配但有 summary，返回 Identity Memory
        if (memory.getSummary() != null && !memory.getSummary().isBlank()) {
            String name = resolveName(memory);
            String result = "聊天对象「" + name + "」\n【Identity】" + memory.getSummary();
            log.debug("[MemoryRAG] legacy fallback identity wxid={}", senderWxid);
            return result;
        }

        log.debug("[MemoryRAG] 无相关记忆 wxid={}", senderWxid);
        return "";
    }

    /**
     * 检查记忆内容是否与消息关键词相关
     */
    private boolean isContentRelevant(String content, String messageLower) {
        if (content == null) return false;
        String contentLower = content.toLowerCase();
        return messageLower.contains(contentLower)
                || contentLower.length() >= 2 && containsAnyWord(messageLower, contentLower);
    }
    /**
     * 从列表中筛选与消息关键词匹配的条目（旧字段兼容）
     */
    private List<String> filterRelevant(List<String> items, String messageLower) {
        if (items == null || items.isEmpty()) return List.of();

        List<String> relevant = new ArrayList<>();
        for (String item : items) {
            String itemLower = item.toLowerCase();
            // 双向子串匹配：条目包含在消息中 或 消息关键词包含在条目中
            if (messageLower.contains(itemLower) || itemLower.length() >= 2 && containsAnyWord(messageLower, itemLower)) {
                relevant.add(item);
            }
        }
        return relevant;
    }

    /**
     * 检查消息中是否包含条目中的任意 2+ 字连续片段
     */
    private boolean containsAnyWord(String messageLower, String itemLower) {
        // 对条目中的每个 2-4 字片段，检查是否出现在消息中
        for (int len = 2; len <= Math.min(4, itemLower.length()); len++) {
            for (int i = 0; i <= itemLower.length() - len; i++) {
                String fragment = itemLower.substring(i, i + len);
                if (messageLower.contains(fragment)) return true;
            }
        }
        return false;
    }

    /**
     * 格式化结构化记忆输出（按 type 分组）
     */
    private String formatStructuredOutput(BotUserMemory memory, List<MemoryEntry> entries) {
        String name = resolveName(memory);
        StringBuilder sb = new StringBuilder();
        sb.append("聊天对象「").append(name).append("」\n");

        // Identity
        if (memory.getSummary() != null && !memory.getSummary().isBlank()) {
            sb.append("【Identity】").append(memory.getSummary()).append("\n");
        }

        // Relationship（实体字段）
        if (memory.getRelationshipType() != null && !memory.getRelationshipType().isBlank()) {
            sb.append("【Relationship】").append(memory.getRelationshipType());
            if (memory.getIntimacyScore() != null) {
                sb.append("，亲密度").append(memory.getIntimacyScore()).append("/10");
            }
            if (memory.getCommunicationStyle() != null && !memory.getCommunicationStyle().isBlank()) {
                sb.append("，").append(memory.getCommunicationStyle());
            }
            sb.append("\n");
        }

        // 按 type 分组输出记忆条目
        Map<String, List<MemoryEntry>> grouped = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getType() != null ? e.getType().toLowerCase() : "unknown",
                        LinkedHashMap::new, Collectors.toList()));

        // 按优先级输出：identity > relationship > preference > episode
        for (String type : List.of("identity", "relationship", "preference", "episode")) {
            List<MemoryEntry> group = grouped.get(type);
            if (group != null && !group.isEmpty()) {
                String label = group.get(0).getTypeLabel();
                String contents = group.stream()
                        .map(MemoryEntry::getContent)
                        .collect(Collectors.joining(", "));
                sb.append(label).append(contents).append("\n");
            }
        }

        return sb.toString().trim();
    }

    /**
     * 格式化旧字段记忆（向后兼容）
     */
    private String formatLegacyMemory(BotUserMemory memory,
                                        List<String> tags, List<String> facts) {
        String name = resolveName(memory);
        StringBuilder sb = new StringBuilder();
        sb.append("聊天对象「").append(name).append("」\n");

        if (memory.getSummary() != null && !memory.getSummary().isBlank()) {
            sb.append("【Identity】").append(memory.getSummary()).append("\n");
        }
        if (tags != null && !tags.isEmpty()) {
            sb.append("【Preference】").append(String.join(", ", tags)).append("\n");
        }
        if (facts != null && !facts.isEmpty()) {
            sb.append("【Episode】").append(String.join(", ", facts)).append("\n");
        }

        return sb.toString().trim();
    }

    private static String resolveName(BotUserMemory memory) {
        return memory.getNickname() != null ? memory.getNickname() : memory.getWxid();
    }
}
