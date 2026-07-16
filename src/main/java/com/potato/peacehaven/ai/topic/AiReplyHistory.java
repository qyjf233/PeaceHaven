package com.potato.peacehaven.ai.topic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * AI 回复历史追踪器
 * <p>
 * 内存维护最近 N 条 AI 回复及其话题标签，
 * 用于检测 AI 是否围绕同一话题反复回复（锚定检测的第二道防线）。
 * </p>
 */
@Slf4j
@Component
public class AiReplyHistory {

    /** 最大保留条数 */
    private static final int MAX_SIZE = 20;

    private final Deque<ReplyEntry> recentReplies = new ConcurrentLinkedDeque<>();

    /**
     * 记录一次 AI 回复
     *
     * @param content AI 回复内容
     * @param topic   回复时的话题（可为 null）
     */
    public void record(String content, String topic) {
        if (content == null || content.isBlank()) return;
        recentReplies.addLast(new ReplyEntry(content, topic, Instant.now()));
        // 超限淘汰最旧的
        while (recentReplies.size() > MAX_SIZE) {
            recentReplies.pollFirst();
        }
        log.debug("[AiReplyHistory] 记录回复 topic={}, 当前历史数={}, reply={}",
                topic, recentReplies.size(),
                content.length() > 40 ? content.substring(0, 40) + "..." : content);
    }

    /**
     * 统计最近回复中指定话题出现的次数
     *
     * @param topic 话题关键词
     * @return 出现次数
     */
    public int getRecentTopicCount(String topic) {
        if (topic == null) return 0;
        int count = 0;
        for (ReplyEntry entry : recentReplies) {
            if (topic.equals(entry.topic())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断指定话题是否在最近回复中过度讨论
     *
     * @param topic     话题
     * @param threshold 阈值
     * @return true=过度讨论
     */
    public boolean isTopicOverused(String topic, int threshold) {
        int count = getRecentTopicCount(topic);
        boolean overused = count >= threshold;
        if (overused) {
            log.info("[AiReplyHistory] 话题过度讨论: topic={}, count={}/{}, total={}",
                    topic, count, threshold, recentReplies.size());
        }
        return overused;
    }

    /**
     * 获取最近回复数量（调试用）
     */
    public int size() {
        return recentReplies.size();
    }

    /** 回复记录 */
    private record ReplyEntry(String content, String topic, Instant timestamp) {}
}
