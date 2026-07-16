package com.potato.peacehaven.ai.retrieval;

import com.potato.peacehaven.ai.embedding.EmbeddingService;
import com.potato.peacehaven.ai.vectorstore.VectorDocument;
import com.potato.peacehaven.ai.vectorstore.VectorSearchResult;
import com.potato.peacehaven.ai.vectorstore.VectorStore;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.BotChatRecord;
import com.potato.peacehaven.repository.BotChatRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 聊天历史记录向量化与检索服务
 * <p>
 * 职责：
 * <ul>
 *   <li>定时将 bot_chat_record 中未处理的本人消息向量化并存入 VectorStore</li>
 *   <li>根据当前消息检索本人历史回复（RAG few-shot 示例）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryRetrievalService {

    private final BotChatRecordRepository chatRecordRepo;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final AiProperties aiProps;

    /**
     * 检索与当前消息最相似的本人历史回复（RAG）
     *
     * @param currentMessage 当前收到的消息
     * @param topK           返回条数
     * @return 相似历史回复列表（按相似度降序），每条包含 content 和 metadata
     */
    public List<RetrievedRecord> retrieve(String currentMessage, int topK) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return Collections.emptyList();
        }

        // 将当前消息向量化
        float[] queryVector = embeddingService.embed(currentMessage);
        if (queryVector == null) {
            log.warn("[RAG] 消息向量化失败，跳过检索");
            return Collections.emptyList();
        }

        // 检索本人历史回复（is_self=true）
        Map<String, String> filters = Map.of("is_self", "true");
        List<VectorSearchResult> results = vectorStore.search(queryVector, topK, filters);

        return results.stream()
                .map(r -> RetrievedRecord.builder()
                        .id(r.getId())
                        .score(r.getScore())
                        .content(r.getMetadata() != null ? r.getMetadata().get("content") : null)
                        .senderNick(r.getMetadata() != null ? r.getMetadata().get("sender_nick") : null)
                        .createTime(r.getMetadata() != null ? r.getMetadata().get("create_time") : null)
                        .build())
                .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 索引新的聊天记录（定时任务 + 手动调用）
     * <p>
     * 查询 processed=false 且 is_self=true 的记录，批量向量化后存入 VectorStore，
     * 并标记 processed=true。
     * </p>
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 30_000) // 每 10 分钟执行一次，启动后延迟 30s
    public void indexNewRecords() {
        if (!aiProps.isReady()) return;

        List<BotChatRecord> pending = chatRecordRepo.findByProcessedFalseOrderByCreateTimeAsc(
                PageRequest.of(0, 100));

        if (pending.isEmpty()) return;

        log.info("[RAG] 发现 {} 条待索引记录，开始向量化...", pending.size());

        // 提取文本内容
        List<String> texts = pending.stream()
                .map(r -> r.getContent() != null ? r.getContent() : "")
                .collect(Collectors.toList());

        // 批量向量化
        float[][] vectors = embeddingService.embedBatch(texts);
        if (vectors == null || vectors.length != pending.size()) {
            log.error("[RAG] 向量化失败（返回数量不匹配），跳过本次索引");
            return;
        }

        // 构建 VectorDocument 列表
        List<VectorDocument> docs = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            BotChatRecord record = pending.get(i);
            if (vectors[i] == null || vectors[i].length == 0) continue;

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("sender_wxid", record.getSenderWxid());
            metadata.put("sender_nick", record.getSenderNick());
            metadata.put("is_self", String.valueOf(record.getIsSelf()));
            metadata.put("content", record.getContent());
            metadata.put("room_id", record.getRoomId());
            if (record.getCreateTime() != null) {
                metadata.put("create_time", String.valueOf(record.getCreateTime()));
            }

            docs.add(VectorDocument.builder()
                    .id("chat_" + record.getId())
                    .vector(vectors[i])
                    .metadata(metadata)
                    .build());
        }

        // 写入 VectorStore
        vectorStore.upsertBatch(docs);

        // 标记为已处理
        for (BotChatRecord record : pending) {
            record.setProcessed(true);
        }
        chatRecordRepo.saveAll(pending);

        log.info("[RAG] 索引完成：写入 {} 条向量，VectorStore 总数={}", docs.size(), vectorStore.count());
    }

    /**
     * RAG 检索结果 DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class RetrievedRecord {
        private String id;
        private double score;
        private String content;
        private String senderNick;
        private String createTime;
    }
}
