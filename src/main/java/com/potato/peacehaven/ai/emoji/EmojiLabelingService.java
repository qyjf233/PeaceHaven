package com.potato.peacehaven.ai.emoji;

import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.BotEmojiLibrary;
import com.potato.peacehaven.repository.BotEmojiLibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表情包 LLM 共识标注服务
 * <p>
 * 核心设计原则：
 * <ul>
 *   <li>标注是<strong>批量共识分析</strong>，不是增量叠加</li>
 *   <li>LLM 一次性查看所有上下文样本，从中识别主流语义和噪音</li>
 *   <li>每次标注输出一组确定的 tags + description，覆盖之前的结果</li>
 *   <li>只有 usageCount >= 阈值的表情包才触发标注（避免单次噪音）</li>
 * </ul>
 * <p>
 * 定时触发：每 12 小时扫描一次未标注的表情包。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmojiLabelingService {

    private final BotEmojiLibraryRepository emojiLibraryRepo;
    private final LlmClient llmClient;
    private final AiProperties aiProps;

    /** 触发标注的最低使用次数阈值 */
    private static final int MIN_USAGE_FOR_LABELING = 3;

    /** 每次定时任务最多标注的表情包数量（控制 LLM 调用成本） */
    private static final int MAX_LABEL_PER_RUN = 5;

    /**
     * 定时标注任务：每 12 小时执行一次
     * <p>
     * 扫描未标注且使用次数达到阈值的表情包，调用 LLM 批量分析上下文样本，
     * 输出共识标签和语义描述。
     * </p>
     */
    @Scheduled(fixedDelayString = "#{${ai.emoji.label-interval-hours:12} * 3600000}",
            initialDelay = 60_000) // 启动后延迟 1 分钟
    public void scheduledLabeling() {
        if (!aiProps.isReady()) {
            log.debug("[EmojiLabel] AI 系统未就绪，跳过标注");
            return;
        }

        List<BotEmojiLibrary> candidates = emojiLibraryRepo
                .findUnlabeledWithMinUsage(MIN_USAGE_FOR_LABELING);

        if (candidates.isEmpty()) {
            log.debug("[EmojiLabel] 无需标注的表情包");
            return;
        }

        log.info("[EmojiLabel] 发现 {} 个待标注表情包（usageCount >= {}）",
                candidates.size(), MIN_USAGE_FOR_LABELING);

        int labeled = 0;
        for (BotEmojiLibrary emoji : candidates) {
            if (labeled >= MAX_LABEL_PER_RUN) break;

            try {
                boolean success = labelEmoji(emoji);
                if (success) labeled++;
            } catch (Exception e) {
                log.error("[EmojiLabel] 标注失败 md5={}", emoji.getMd5(), e);
            }
        }

        log.info("[EmojiLabel] 本轮标注完成：成功 {} 个", labeled);
    }

    /**
     * 对单个表情包执行 LLM 共识标注
     *
     * @return 是否标注成功
     */
    private boolean labelEmoji(BotEmojiLibrary emoji) {
        String samples = emoji.getContextSamples();
        if (samples == null || samples.isBlank() || "[]".equals(samples)) {
            log.debug("[EmojiLabel] 表情包无上下文样本，跳过 md5={}", emoji.getMd5());
            return false;
        }

        // 格式化上下文样本为可读文本
        String formattedSamples = formatSamplesForLlm(samples);
        if (formattedSamples == null || formattedSamples.isBlank()) {
            log.debug("[EmojiLabel] 上下文样本格式化失败，跳过 md5={}", emoji.getMd5());
            return false;
        }

        log.info("[EmojiLabel] 开始标注 md5={}, usageCount={}, sampleCount={}",
                emoji.getMd5(), emoji.getUsageCount(), countSamples(samples));

        // 构建 LLM 提示（共识分析）
        String prompt = buildLabelingPrompt(formattedSamples, emoji.getUsageCount());

        List<LlmMessage> messages = List.of(
                LlmMessage.system("你是一个表情包语义分析专家。你需要根据表情包的多个使用场景，分析出它的共识含义。"),
                LlmMessage.user(prompt)
        );

        // 低温度确保稳定输出
        String result = llmClient.chat(messages, 0.3, 300);
        if (result == null || result.isBlank()) {
            log.warn("[EmojiLabel] LLM 返回为空 md5={}", emoji.getMd5());
            return false;
        }

        // 解析 LLM 输出（格式：TAGS: xxx\nDESC: xxx）
        String tags = extractLine(result, "TAGS:");
        String desc = extractLine(result, "DESC:");

        if (desc == null || desc.isBlank()) {
            log.warn("[EmojiLabel] LLM 未返回有效描述 md5={}, result={}", emoji.getMd5(), result);
            return false;
        }

        // 覆盖写入（共识标注，不是追加）
        emoji.setTags(tags);
        emoji.setDescription(desc);
        emoji.setLabeled(true);
        emojiLibraryRepo.save(emoji);

        log.info("[EmojiLabel] 标注完成 md5={}, tags={}, desc={}",
                emoji.getMd5(), tags, desc.length() > 50 ? desc.substring(0, 50) + "..." : desc);
        return true;
    }

    /**
     * 构建共识标注 Prompt
     * <p>
     * 核心指令：分析所有使用场景，找到<strong>共识语义</strong>，
     * 忽略噪音（乱发、与上下文无关的使用）。
     * </p>
     */
    private String buildLabelingPrompt(String formattedSamples, int usageCount) {
        return """
                以下是一个微信表情包在 %d 次使用中的上下文记录。
                每次使用包含：发送者、发送前最近几条聊天消息。
                
                请分析这些使用场景，找出这个表情包的**共识含义**。
                
                分析要求：
                1. 关注多次出现的相似语境，这是主流语义
                2. 忽略明显乱发或与上下文无关的使用（噪音）
                3. 如果场景差异很大，尝试找到它们的共同点
                4. 输出应该是大多数人使用这个表情时想表达的意思
                
                输出格式（严格遵守，不要加其他内容）：
                TAGS: 用逗号分隔的2-4个关键词标签（如：无语,摆烂,躺平）
                DESC: 一句话描述这个表情包的含义（15字以内，如：表达无奈躺平不想说话）
                
                使用记录：
                %s
                """.formatted(usageCount, formattedSamples);
    }

    /**
     * 将 JSON 格式的上下文样本转为 LLM 可读的纯文本
     */
    private String formatSamplesForLlm(String samplesJson) {
        if (samplesJson == null || samplesJson.isBlank()) return null;

        try {
            StringBuilder sb = new StringBuilder();
            String content = samplesJson.trim();
            if (content.startsWith("[")) content = content.substring(1);
            if (content.endsWith("]")) content = content.substring(0, content.length() - 1);

            int sampleNum = 1;
            int idx = 0;
            int skippedDuplicates = 0;
            java.util.Set<String> seenContexts = new java.util.HashSet<>();

            while (idx < content.length()) {
                int start = content.indexOf("{", idx);
                if (start < 0) break;
                int end = findMatchingBrace(content, start);
                if (end < 0) break;

                String sample = content.substring(start, end + 1);

                // 提取 before 数组内容作为去重 key
                String beforeKey = "";
                int beforeStart = sample.indexOf("\"before\":[");
                if (beforeStart >= 0) {
                    int arrStart = sample.indexOf("[", beforeStart);
                    int arrEnd = sample.indexOf("]", arrStart);
                    if (arrStart >= 0 && arrEnd >= 0) {
                        beforeKey = sample.substring(arrStart, arrEnd + 1);
                    }
                }

                // 跳过重复上下文（同一场景发送多次无额外信息价值）
                if (!beforeKey.isEmpty() && !seenContexts.add(beforeKey)) {
                    skippedDuplicates++;
                    idx = end + 1;
                    continue;
                }

                sb.append("【场景 ").append(sampleNum++).append("】\n");

                String sender = extractJsonField(sample, "sender");
                if (sender != null) sb.append("发送者: ").append(sender).append("\n");

                if (beforeStart >= 0) {
                    int arrStart = sample.indexOf("[", beforeStart);
                    int arrEnd = sample.indexOf("]", arrStart);
                    if (arrStart >= 0 && arrEnd >= 0) {
                        String arr = sample.substring(arrStart + 1, arrEnd);
                        sb.append("发送前聊天:\n");
                        String[] parts = arr.split("\",\"");
                        for (String part : parts) {
                            String cleaned = part.replace("\"", "").trim();
                            if (!cleaned.isEmpty()) {
                                sb.append("  ").append(cleaned).append("\n");
                            }
                        }
                    }
                }
                sb.append("\n");
                idx = end + 1;
            }

            if (skippedDuplicates > 0) {
                log.debug("[EmojiLabel] 去重跳过 {} 条重复上下文", skippedDuplicates);
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("[EmojiLabel] JSON 样本解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 找到匹配的右花括号 */
    private int findMatchingBrace(String str, int openPos) {
        int depth = 0;
        for (int i = openPos; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 从 JSON 对象字符串中提取字段值（简易解析） */
    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /** 从 LLM 输出中按前缀提取行内容 */
    private String extractLine(String text, String prefix) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** 简易统计样本数 */
    private int countSamples(String samplesJson) {
        if (samplesJson == null) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = samplesJson.indexOf("{\"", idx)) >= 0) {
            count++;
            idx += 2;
        }
        return count;
    }

    /**
     * 手动触发标注（供管理 API 调用）
     */
    public int triggerLabeling() {
        if (!aiProps.isReady()) return 0;
        List<BotEmojiLibrary> candidates = emojiLibraryRepo
                .findUnlabeledWithMinUsage(MIN_USAGE_FOR_LABELING);
        int labeled = 0;
        for (BotEmojiLibrary emoji : candidates) {
            try {
                if (labelEmoji(emoji)) labeled++;
            } catch (Exception e) {
                log.error("[EmojiLabel] 手动标注失败 md5={}", emoji.getMd5(), e);
            }
        }
        return labeled;
    }
}
