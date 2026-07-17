package com.potato.peacehaven.ai.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 结构化记忆条目
 * <p>
 * 每条记忆携带完整的元数据（类型、重要性、可信度、TTL），
 * 以 JSON 数组形式存储在 BotUserMemory.structuredMemories 列中。
 * </p>
 * <p>
 * 记忆类型：
 * <ul>
 *   <li>identity —— 稳定身份信息（职业、性格、价值观）</li>
 *   <li>preference —— 兴趣偏好（爱好、口味、习惯）</li>
 *   <li>episode —— 发生过的事件 / 经历</li>
 *   <li>relationship —— 与本人的人际关系</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryEntry {

    /** 唯一 ID（UUID） */
    @Builder.Default
    private String id = UUID.randomUUID().toString().substring(0, 8);

    /** 记忆类型：identity / preference / episode / relationship */
    private String type;

    /** 记忆内容 */
    private String content;

    /** 重要性评分（0-1），低于阈值的记忆不应被存储 */
    private double importance;

    /** LLM 提取可信度（0-1） */
    private double confidence;

    /** 存活天数：0=永久，30=30天，180=半年 */
    private int ttlDays;

    /** 创建时间（epoch 秒，避免 LocalDateTime JSON 序列化问题） */
    @Builder.Default
    private long createdAt = Instant.now().getEpochSecond();

    /** 来源对话片段（调试追溯用） */
    private String source;

    /** 生成该记忆的 Prompt 版本号 */
    private String promptVersion;

    /**
     * 判断此记忆是否已过期
     * <p>ttlDays=0 表示永久记忆，永不过期</p>
     */
    public boolean isExpired() {
        if (ttlDays <= 0) return false;
        long daysSinceCreation = (Instant.now().getEpochSecond() - createdAt) / 86400;
        return daysSinceCreation >= ttlDays;
    }

    /**
     * 判断是否为永久记忆
     */
    public boolean isPermanent() {
        return ttlDays <= 0;
    }

    /**
     * 获取类型的中文标签（用于 Prompt 格式化输出）
     */
    public String getTypeLabel() {
        if (type == null) return "【Unknown】";
        return switch (type.toLowerCase()) {
            case "identity" -> "【Identity】";
            case "preference" -> "【Preference】";
            case "episode" -> "【Episode】";
            case "relationship" -> "【Relationship】";
            default -> "【" + type + "】";
        };
    }
}
