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
     * <p>采用多层多样性过滤：
     * 1. bigram Jaccard 去重（整体文本相似）
     * 2. 关键词频率限制（同一关键词最多出现 N 次，防止话题集中）
     * </p>
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

        // 过量检索，为多样性过滤留余量
        int fetchK = topK * 4;
        Map<String, String> filters = Map.of("is_self", "true");
        List<VectorSearchResult> results = vectorStore.search(queryVector, fetchK, filters);

        // 多样性过滤参数
        double diversityThreshold = 0.4;
        int maxKeywordRepeat = 2; // 同一关键词最多出现在 N 条记录中
        List<RetrievedRecord> diverse = new ArrayList<>();
        List<Set<String>> selectedBigrams = new ArrayList<>();
        Map<String, Integer> keywordFreq = new HashMap<>(); // 关键词出现次数

        for (VectorSearchResult r : results) {
            String content = (r.getMetadata() != null) ? r.getMetadata().get("content") : null;
            if (content == null || content.isBlank()) continue;

            // 1. Bigram 相似度检查
            Set<String> bigrams = charBigrams(content);
            boolean tooSimilar = false;
            for (Set<String> existing : selectedBigrams) {
                if (jaccardSimilarity(bigrams, existing) > diversityThreshold) {
                    tooSimilar = true;
                    break;
                }
            }
            if (tooSimilar) continue;

            // 2. 关键词频率检查
            Set<String> keywords = extractKeywords(content);
            boolean keywordOverused = false;
            for (String kw : keywords) {
                Integer count = keywordFreq.get(kw);
                if (count != null && count >= maxKeywordRepeat) {
                    keywordOverused = true;
                    break;
                }
            }
            if (keywordOverused) continue;

            // 通过过滤，加入结果集
            diverse.add(RetrievedRecord.builder()
                    .id(r.getId())
                    .score(r.getScore())
                    .content(content)
                    .senderNick(r.getMetadata() != null ? r.getMetadata().get("sender_nick") : null)
                    .createTime(r.getMetadata() != null ? r.getMetadata().get("create_time") : null)
                    .build());
            selectedBigrams.add(bigrams);
            // 更新关键词频率
            for (String kw : keywords) {
                keywordFreq.merge(kw, 1, Integer::sum);
            }

            if (diverse.size() >= topK) break;
        }

        int filtered = results.size() - diverse.size();
        if (filtered > 0) {
            log.info("[RAG] 多样性过滤：{}/{} 条通过 (fetchK={}, topK={})", diverse.size(), results.size(), fetchK, topK);
        }
        return diverse;
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
     * 提取文本中的关键词（2-4字中文词组 + 非停用词）
     * <p>简单的中文关键词提取，无需依赖分词库</p>
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的是", "不是", "可以", "就是", "还是", "或者", "这个", "那个", "什么",
            "怎么", "没有", "一个", "我们", "你们", "他们", "自己", "现在",
            "然后", "因为", "所以", "如果", "但是", "而且", "已经", "知道",
            "觉得", "真的", "其实", "应该", "不要", "好的", "哈哈哈", "呵呵"
    );

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        String clean = text.replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）…—·@#\\$%&\\*\\+=/\\\\<>\\[\\]{}|~`^]+", " ");
        String[] words = clean.split("\\s+");
        for (String w : words) {
            w = w.trim();
            if (w.length() >= 2 && w.length() <= 4 && !STOP_WORDS.contains(w)) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    /**
     * 计算两个集合的 Jaccard 相似度
     */
    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return (double) intersection / union;
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
