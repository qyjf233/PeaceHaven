package com.potato.peacehaven.ai.retrieval;

import com.potato.peacehaven.ai.embedding.EmbeddingService;
import com.potato.peacehaven.ai.topic.TopicExtractor;
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
    private final TopicExtractor topicExtractor;

    /**
     * 检索与当前消息最相似的本人历史回复（RAG — Style RAG）
     * <p>采用 MMR (Max Marginal Relevance) 重排算法：
     * MMR(d) = lambda * Sim(d, Q) - (1-lambda) * max Sim(d, d')
     * 在相关性和多样性之间取得平衡，避免同一话题的历史记录霸占结果。
     * </p>
     *
     * @param currentMessage 当前收到的消息
     * @param topK           返回条数
     * @return 相似历史回复列表（按 MMR 分数排序）
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

        // 过量检索，为 MMR 重排留余量
        int fetchK = topK * 4;
        Map<String, String> filters = Map.of("is_self", "true");
        List<VectorSearchResult> candidates = vectorStore.search(queryVector, fetchK, filters);

        if (candidates.isEmpty()) return Collections.emptyList();

        // MMR 重排
        double lambda = 0.7; // 偏向相关性，兼顾多样性
        List<VectorSearchResult> selected = mmrRerank(candidates, queryVector, topK, lambda);

        int filtered = candidates.size() - selected.size();
        if (filtered > 0) {
            log.info("[RAG] MMR 重排：{}/{} 条通过 (过滤 {} 条, fetchK={}, topK={}, lambda={})",
                    selected.size(), candidates.size(), filtered, fetchK, topK, lambda);
        }

        return selected.stream()
                .map(r -> RetrievedRecord.builder()
                        .id(r.getId())
                        .score(r.getScore())
                        .content(r.getMetadata() != null ? r.getMetadata().get("content") : null)
                        .senderNick(r.getMetadata() != null ? r.getMetadata().get("sender_nick") : null)
                        .createTime(r.getMetadata() != null ? r.getMetadata().get("create_time") : null)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * MMR (Max Marginal Relevance) 重排算法
     * <p>
     * 迭代选取使得 [lambda * Sim(d,Q) - (1-lambda) * max Sim(d,d')] 最大的候选文档，
     * 确保结果既相关又多样。
     * </p>
     */
    private List<VectorSearchResult> mmrRerank(List<VectorSearchResult> candidates,
                                                 float[] queryVector, int topK, double lambda) {
        List<VectorSearchResult> selected = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();

        // 预计算每个候选的查询向量（metadata 中没有存向量，需要重新计算 content 的向量开销太大）
        // 改用：直接使用余弦相似度得分（已在 search 中计算）作为 Sim(d,Q)
        // 候选间的相似度用 bigram Jaccard 近似（避免二次向量化）

        for (int round = 0; round < topK && round < candidates.size(); round++) {
            double bestMmr = Double.NEGATIVE_INFINITY;
            VectorSearchResult bestCandidate = null;

            for (VectorSearchResult candidate : candidates) {
                if (selectedIds.contains(candidate.getId())) continue;

                String content = candidate.getMetadata() != null ? candidate.getMetadata().get("content") : null;
                if (content == null || content.isBlank()) continue;

                // Sim(d, Q) = 已计算的余弦相似度分数
                double simQuery = candidate.getScore();

                // max Sim(d, d') = 与已选文档的最大相似度
                double maxSimSelected = 0.0;
                for (VectorSearchResult sel : selected) {
                    String selContent = sel.getMetadata() != null ? sel.getMetadata().get("content") : null;
                    if (selContent == null) continue;
                    double sim = bigramJaccard(content, selContent);
                    if (sim > maxSimSelected) maxSimSelected = sim;
                }

                // MMR = lambda * Sim(d,Q) - (1-lambda) * maxSim(d,d')
                double mmr = lambda * simQuery - (1 - lambda) * maxSimSelected;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestCandidate = candidate;
                }
            }

            if (bestCandidate != null) {
                selected.add(bestCandidate);
                selectedIds.add(bestCandidate.getId());
            } else {
                break; // 没有更多有效候选
            }
        }

        return selected;
    }

    /**
     * 字符 bigram Jaccard 相似度（快速文本相似度，无需向量化）
     */
    private double bigramJaccard(String a, String b) {
        Set<String> bigramsA = charBigrams(a);
        Set<String> bigramsB = charBigrams(b);
        if (bigramsA.isEmpty() && bigramsB.isEmpty()) return 1.0;
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0;
        long intersection = bigramsA.stream().filter(bigramsB::contains).count();
        long union = bigramsA.size() + bigramsB.size() - intersection;
        return (double) intersection / union;
    }

    /**
     * 提取文本的字符 bigram 集合（用于快速文本相似度比较）
     */
    private Set<String> charBigrams(String text) {
        Set<String> bigrams = new HashSet<>();
        String normalized = text.replaceAll("\\s+", "").toLowerCase();
        for (int i = 0; i < normalized.length() - 1; i++) {
            bigrams.add(normalized.substring(i, i + 2));
        }
        return bigrams;
    }

    /**
     * 索引新的聊天记录（定时任务 + 手动调用）
     * <p>
     * 查询 processed=false 的记录，批量向量化后存入 VectorStore，
     * 并标记 processed=true。AI 生成的回复（is_bot_reply=true）仅标记已处理，不纳入向量库。
     * </p>
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 30_000) // 每 10 分钟执行一次，启动后延迟 30s
    public void indexNewRecords() {
        if (!aiProps.isReady()) return;

        List<BotChatRecord> pending = chatRecordRepo.findByProcessedFalseOrderByCreateTimeAsc(
                PageRequest.of(0, 100));

        if (pending.isEmpty()) return;

        // 分离 AI 回复和真实消息：AI 回复不纳入向量库（防止风格回流）
        List<BotChatRecord> toIndex = pending.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsBotReply()))
                .collect(Collectors.toList());
        long botReplyCount = pending.size() - toIndex.size();
        if (botReplyCount > 0) {
            log.info("[RAG] 跳过 {} 条 AI 回复记录（不纳入训练）", botReplyCount);
        }

        if (toIndex.isEmpty()) {
            // 全部是 AI 回复，直接标记已处理
            for (BotChatRecord record : pending) {
                record.setProcessed(true);
            }
            chatRecordRepo.saveAll(pending);
            return;
        }

        log.info("[RAG] 发现 {} 条待索引记录（{} 条 AI 回复已排除），开始向量化...", toIndex.size(), botReplyCount);

        // 提取文本内容
        List<String> texts = toIndex.stream()
                .map(r -> r.getContent() != null ? r.getContent() : "")
                .collect(Collectors.toList());

        // 批量向量化
        float[][] vectors = embeddingService.embedBatch(texts);
        if (vectors == null || vectors.length != toIndex.size()) {
            log.error("[RAG] 向量化失败（返回数量不匹配），跳过本次索引");
            return;
        }

        // 构建 VectorDocument 列表
        List<VectorDocument> docs = new ArrayList<>();
        for (int i = 0; i < toIndex.size(); i++) {
            BotChatRecord record = toIndex.get(i);
            if (vectors[i] == null || vectors[i].length == 0) continue;

            // 提取话题并存入 metadata（用于后续按话题过滤 RAG 结果）
            String topic = null;
            try {
                topic = topicExtractor.extract(record.getContent());
            } catch (Exception e) {
                log.debug("[RAG] 话题提取失败: {}", e.getMessage());
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("sender_wxid", record.getSenderWxid());
            metadata.put("sender_nick", record.getSenderNick());
            metadata.put("is_self", String.valueOf(record.getIsSelf()));
            metadata.put("content", record.getContent());
            metadata.put("room_id", record.getRoomId());
            if (topic != null) {
                metadata.put("topic", topic);
            }
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

        // 标记所有记录（包括 AI 回复）为已处理
        for (BotChatRecord record : pending) {
            record.setProcessed(true);
        }
        chatRecordRepo.saveAll(pending);

        log.info("[RAG] 索引完成：写入 {} 条向量（跳过 {} 条 AI 回复），VectorStore 总数={}", docs.size(), botReplyCount, vectorStore.count());
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
