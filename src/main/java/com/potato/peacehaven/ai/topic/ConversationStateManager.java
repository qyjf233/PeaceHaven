package com.potato.peacehaven.ai.topic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.config.AiProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
public class ConversationStateManager {

    private final AiProperties aiProps;

    /** chatroomId -> ConversationState */
    private final ConcurrentHashMap<String, ConversationState> states = new ConcurrentHashMap<>();

    private static final Path SNAPSHOT_DIR = Path.of("data", "cache");
    private static final File SNAPSHOT_FILE = SNAPSHOT_DIR.resolve("conversation-state.json").toFile();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationStateManager(AiProperties aiProps) {
        this.aiProps = aiProps;
    }

    @PostConstruct
    public void loadFromDisk() {
        if (!SNAPSHOT_FILE.exists()) return;
        try {
            Map<String, Map<String, Object>> loaded = objectMapper.readValue(SNAPSHOT_FILE,
                    new TypeReference<Map<String, Map<String, Object>>>() {});
            for (var entry : loaded.entrySet()) {
                Map<String, Object> m = entry.getValue();
                ConversationState state = new ConversationState();
                state.setCurrentTopic((String) m.get("currentTopic"));
                state.setTopicMentionCount(toInt(m.get("topicMentionCount")));
                state.setPreviousTopic((String) m.get("previousTopic"));
                if (m.get("topicStartEpoch") != null) {
                    state.setTopicStartTime(Instant.ofEpochSecond(((Number) m.get("topicStartEpoch")).longValue()));
                }
                if (m.get("lastChangeEpoch") != null) {
                    state.setLastTopicChangeTime(Instant.ofEpochSecond(((Number) m.get("lastChangeEpoch")).longValue()));
                }
                states.put(entry.getKey(), state);
            }
            log.info("[ConvState] 从磁盘加载 {} 个对话状态", states.size());
        } catch (Exception e) {
            log.warn("[ConvState] 磁盘快照加载失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void saveToDisk() {
        if (states.isEmpty()) return;
        try {
            Files.createDirectories(SNAPSHOT_DIR);
            Map<String, Map<String, Object>> serializable = new HashMap<>();
            for (var entry : states.entrySet()) {
                ConversationState s = entry.getValue();
                Map<String, Object> m = new HashMap<>();
                m.put("currentTopic", s.getCurrentTopic());
                m.put("topicMentionCount", s.getTopicMentionCount());
                m.put("previousTopic", s.getPreviousTopic());
                m.put("topicStartEpoch", s.getTopicStartTime() != null ? s.getTopicStartTime().getEpochSecond() : null);
                m.put("lastChangeEpoch", s.getLastTopicChangeTime() != null ? s.getLastTopicChangeTime().getEpochSecond() : null);
                serializable.put(entry.getKey(), m);
            }
            objectMapper.writeValue(SNAPSHOT_FILE, serializable);
            log.info("[ConvState] 快照写入磁盘: {} 个状态", states.size());
        } catch (Exception e) {
            log.error("[ConvState] 快照写入失败: {}", e.getMessage());
        }
    }

    private static int toInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

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
