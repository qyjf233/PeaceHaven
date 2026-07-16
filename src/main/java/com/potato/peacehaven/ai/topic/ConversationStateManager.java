package com.potato.peacehaven.ai.topic;

import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话状态管理器
 * <p>
 * 每个群聊维护一份 {@link ConversationState}，追踪话题切换和持续情况。
 * 内存实现，重启后状态丢失（可接受，新对话自然积累）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationStateManager {

    private final AiProperties aiProps;

    /** chatroomId -> ConversationState */
    private final ConcurrentHashMap<String, ConversationState> states = new ConcurrentHashMap<>();

    /**
     * 更新对话状态
     * <p>
     * 如果提取到的话题与当前话题相同，计数+1；
     * 如果话题切换，重置计数并记录上一个话题。
     * 如果 topic 为 null（噪音消息），不更新。
     * </p>
     *
     * @param chatroomId 群聊 ID
     * @param topic      提取到的话题（null 表示无话题）
     */
    public void update(String chatroomId, String topic) {
        if (chatroomId == null || topic == null) return;

        states.compute(chatroomId, (key, state) -> {
            if (state == null) {
                // 首次记录
                state = new ConversationState();
                state.setCurrentTopic(topic);
                state.setTopicMentionCount(1);
                state.setTopicStartTime(Instant.now());
                state.setLastTopicChangeTime(Instant.now());
                log.debug("[ConvState] 新建状态 chatroom={}, topic={}", chatroomId, topic);
                return state;
            }

            if (topic.equals(state.getCurrentTopic())) {
                // 同一话题，计数+1
                state.setTopicMentionCount(state.getTopicMentionCount() + 1);
                log.debug("[ConvState] 话题持续 chatroom={}, topic={}, count={}",
                        chatroomId, topic, state.getTopicMentionCount());
            } else {
                // 话题切换
                state.setPreviousTopic(state.getCurrentTopic());
                state.setCurrentTopic(topic);
                state.setTopicMentionCount(1);
                state.setTopicStartTime(Instant.now());
                state.setLastTopicChangeTime(Instant.now());
                log.info("[ConvState] 话题切换 chatroom={}, {} -> {}", chatroomId, state.getPreviousTopic(), topic);
            }
            return state;
        });
    }

    /**
     * 获取指定群聊的对话状态
     *
     * @param chatroomId 群聊 ID
     * @return 状态对象，不存在时返回 null
     */
    public ConversationState getState(String chatroomId) {
        return chatroomId != null ? states.get(chatroomId) : null;
    }

    /**
     * 判断指定群聊的当前话题是否"过热"
     */
    public boolean isTopicStale(String chatroomId) {
        ConversationState state = getState(chatroomId);
        if (state == null) return false;
        int threshold = aiProps.getReply().getTopicStaleThreshold();
        boolean stale = state.isTopicStale(threshold);
        if (stale) {
            log.info("[ConvState] 话题过热 chatroom={}, topic={}, count={}/{}, duration={}s",
                    chatroomId, state.getCurrentTopic(), state.getTopicMentionCount(),
                    threshold, state.topicDurationSeconds());
        }
        return stale;
    }
}
