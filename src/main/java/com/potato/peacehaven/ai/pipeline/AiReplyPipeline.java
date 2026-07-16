package com.potato.peacehaven.ai.pipeline;

import com.potato.peacehaven.ai.decision.ReplyDecision;
import com.potato.peacehaven.ai.decision.ReplyDecisionService;
import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.llm.LlmReply;
import com.potato.peacehaven.ai.memory.UserMemoryExtractor;
import com.potato.peacehaven.ai.memory.UserMemoryService;
import com.potato.peacehaven.ai.prompt.PromptBuilder;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService.RetrievedRecord;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService.ContextMessage;
import com.potato.peacehaven.ai.retrieval.MemoryRagService;
import com.potato.peacehaven.ai.review.ReplyReviewService;
import com.potato.peacehaven.ai.review.ReviewResult;
import com.potato.peacehaven.ai.summary.ConversationSummaryService;
import com.potato.peacehaven.ai.topic.*;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.service.WechatApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * AI 回复流水线编排器
 * <p>
 * 核心流程：决策 → 上下文 + RAG + 用户记忆 → 构建 Prompt → 调用 LLM → 审核 → 发送
 * <p>
 * 整体异步执行（@Async），不阻塞 webhook 3s 超时。
 * 发送前随机延迟 1-3s，模拟人类反应时间。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiReplyPipeline {

    private final AiProperties aiProps;
    private final ReplyDecisionService decisionService;
    private final ContextRetrievalService contextRetrievalService;
    private final ChatHistoryRetrievalService chatHistoryRetrievalService;
    private final MemoryRagService memoryRagService;
    private final UserMemoryService userMemoryService;
    private final UserMemoryExtractor userMemoryExtractor;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ReplyReviewService reviewService;
    private final WechatApiService wechatApiService;
    private final AiReplyTracker aiReplyTracker;
    private final ConversationSummaryService summaryService;
    private final TopicExtractor topicExtractor;
    private final ConversationStateManager conversationStateManager;
    private final TopicJudgeService topicJudgeService;
    private final AiReplyHistory aiReplyHistory;

    /**
     * 处理群消息（异步执行）
     *
     * @param chatroomId  群聊 ID
     * @param senderWxid  发送者 wxid
     * @param senderNick  发送者昵称
     * @param content     消息内容
     * @param isMentioned 是否被 @提及
     */
    @Async("aiReplyExecutor")
    public void processGroupMessage(String chatroomId, String senderWxid,
                                     String senderNick, String content,
                                     boolean isMentioned) {
        if (!aiProps.isReady()) {
            log.info("[Pipeline] ❗ AI 系统未就绪（isReady=false），跳过处理");
            return;
        }

        try {
            doProcess(chatroomId, senderWxid, senderNick, content, isMentioned);
        } catch (Exception e) {
            log.error("[Pipeline] 处理异常 chatroom={}, sender={}", chatroomId, senderWxid, e);
        }
    }

    private void doProcess(String chatroomId, String senderWxid,
                            String senderNick, String content,
                            boolean isMentioned) throws InterruptedException {

        long startTime = System.currentTimeMillis();
        log.info("[Pipeline] 开始处理 chatroom={}, sender={}({}), mentioned={}, content={}",
                chatroomId, senderNick, senderWxid, isMentioned,
                content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content);

        // ===== 1. 决策 =====
        ReplyDecision decision = decisionService.decide(chatroomId, senderWxid, content, isMentioned);
        if (!decision.isShouldReply()) {
            log.info("[Pipeline] 决策跳过: {}", decision.getReason());
            return;
        }
        log.info("[Pipeline] 决策回复: {}", decision.getReason());

        AiProperties.ReplyConfig replyCfg = aiProps.getReply();
        boolean topicAware = replyCfg.isTopicAware();

        // ===== 2. 话题提取 + 状态更新 =====
        String currentTopic = null;
        if (topicAware) {
            currentTopic = topicExtractor.extract(content);
            conversationStateManager.update(chatroomId, currentTopic);
            log.info("[Pipeline] 话题提取 topic={}, chatroom={}", currentTopic, chatroomId);
        }

        // ===== 3. RAG 必要性判断 =====
        boolean needsRag = topicJudgeService.needsRagLookup(content, chatroomId);
        log.info("[Pipeline] RAG 判断 needsRag={}", needsRag);

        // ===== 4. 拉取最近上下文 + 生成摘要 =====
        List<ContextMessage> contextMessages = contextRetrievalService.getRecentContext(
                chatroomId, replyCfg.getContextSize());
        log.info("[Pipeline] 拉取上下文 {} 条", contextMessages.size());

        String conversationSummary = "";
        if (replyCfg.isUseConversationSummary()) {
            conversationSummary = summaryService.summarize(chatroomId, contextMessages);
            log.info("[Pipeline] 对话摘要: {}",
                    conversationSummary.length() > 80 ? conversationSummary.substring(0, 80) + "..." : conversationSummary);
        }

        // ===== 5. Style RAG（条件：TopicJudge 通过） =====
        List<RetrievedRecord> ragRecords = Collections.emptyList();
        if (needsRag) {
            try {
                ragRecords = chatHistoryRetrievalService.retrieve(content, replyCfg.getRagTopK());
                log.info("[Pipeline] Style RAG 检索 {} 条", ragRecords.size());
            } catch (Exception e) {
                log.warn("[Pipeline] Style RAG 检索失败，继续无 RAG: {}", e.getMessage());
            }
        } else {
            log.info("[Pipeline] 跳过 Style RAG（无需检索）");
        }

        // ===== 6. Memory RAG（条件：senderWxid 有记忆） =====
        String memoryText = "";
        if (needsRag && senderWxid != null) {
            try {
                memoryText = memoryRagService.retrieveRelevantMemory(senderWxid, content);
                if (!memoryText.isBlank()) {
                    log.info("[Pipeline] Memory RAG 命中: {}",
                            memoryText.length() > 80 ? memoryText.substring(0, 80) + "..." : memoryText);
                }
            } catch (Exception e) {
                log.warn("[Pipeline] Memory RAG 失败: {}", e.getMessage());
            }
        }

        // 兼容：如果 Memory RAG 未命中且 topicAware 关闭，fallback 到旧的 UserMemoryService
        if (memoryText.isBlank() && !topicAware) {
            try {
                var memoryOpt = userMemoryService.getUserMemory(senderWxid);
                if (memoryOpt.isPresent()) {
                    memoryText = userMemoryService.formatMemoryForPrompt(memoryOpt.get());
                }
            } catch (Exception e) {
                log.warn("[Pipeline] 用户记忆加载失败: {}", e.getMessage());
            }
        }

        // ===== 7. 检查话题过热（反锚定提示） =====
        String antiAnchoringHint = null;
        if (topicAware && currentTopic != null) {
            boolean convStale = conversationStateManager.isTopicStale(chatroomId);
            boolean historyStale = aiReplyHistory.isTopicOverused(currentTopic, replyCfg.getTopicStaleThreshold());
            if (convStale || historyStale) {
                antiAnchoringHint = aiProps.getPrompt().getAntiAnchoringHint();
                log.info("[Pipeline] 话题过热，注入反锚定提示 convStale={}, historyStale={}, topic={}",
                        convStale, historyStale, currentTopic);
            }
        }

        // ===== 8. 构建 Prompt =====
        List<LlmMessage> messages = promptBuilder.buildMessages(
                senderNick, content, conversationSummary, memoryText, ragRecords, antiAnchoringHint);
        boolean jsonMode = aiProps.getPrompt().isJsonReplyFormat();
        log.info("[Pipeline] Prompt 构建完成 msgs={}, persona={}, version={}, jsonMode={}",
                messages.size(), aiProps.getPrompt().getPersonaName(),
                com.potato.peacehaven.ai.prompt.PromptBuilder.PROMPT_VERSION, jsonMode);

        // ===== 9. 调用 LLM =====
        AiProperties.LlmConfig llmCfg = aiProps.getLlm();
        String rawReply = llmClient.chat(messages, llmCfg.getTemperature(), llmCfg.getMaxTokens());
        if (rawReply == null || rawReply.isBlank()) {
            log.warn("[Pipeline] LLM 返回空，跳过发送");
            return;
        }

        // ===== 9b. 解析 LLM 输出（JSON 模式 / 纯文本 fallback） =====
        String aiReply;
        if (jsonMode) {
            LlmReply parsed = LlmReply.parse(rawReply);
            if (parsed != null) {
                aiReply = parsed.getReply();
                log.info("[Pipeline] LLM 结构化输出: confidence={}, memoryUsed={}, reason={}, updateMem={}, reply={}",
                        String.format("%.2f", parsed.getConfidence()),
                        parsed.getMemoryUsed(),
                        parsed.getReplyReason(),
                        parsed.isShouldUpdateMemory(),
                        aiReply != null && aiReply.length() > 80 ? aiReply.substring(0, 80) + "..." : aiReply);
            } else {
                log.warn("[Pipeline] JSON 解析失败，fallback 到原始文本: {}",
                        rawReply.length() > 80 ? rawReply.substring(0, 80) + "..." : rawReply);
                aiReply = rawReply;
            }
        } else {
            aiReply = rawReply;
            log.info("[Pipeline] LLM 回复: {}",
                    aiReply.length() > 100 ? aiReply.substring(0, 100) + "..." : aiReply);
        }

        // ===== 10. 审核 =====
        ReviewResult review = reviewService.review(content, aiReply);
        if (!review.isApproved()) {
            log.info("[Pipeline] 审核未通过: {}", review.getReason());
            return;
        }
        String finalReply = review.getReply();

        // ===== 11. 模拟人类延迟（1-3s 随机） =====
        long delayMs = ThreadLocalRandom.current().nextLong(1000, 3001);
        log.info("[Pipeline] 模拟人类延迟 {}ms", delayMs);
        Thread.sleep(delayMs);

        // ===== 12. 发送消息 =====
        String sendTarget = (chatroomId != null && !chatroomId.isBlank()) ? chatroomId : senderWxid;
        boolean isGroupChat = chatroomId != null && !chatroomId.isBlank();
        log.info("[Pipeline] 准备发送 target={}, isGroup={}, reply={}",
                sendTarget, isGroupChat, finalReply.length() > 80 ? finalReply.substring(0, 80) + "..." : finalReply);

        var resp = wechatApiService.sendText(sendTarget, finalReply);
        if (resp.isSuccess()) {
            log.info("[Pipeline] 发送成功 target={}, reply={}", sendTarget,
                    finalReply.length() > 50 ? finalReply.substring(0, 50) + "..." : finalReply);
            // 注册 AI 回复指纹
            aiReplyTracker.register(finalReply);
            // 更新决策统计
            decisionService.recordReply(chatroomId != null ? chatroomId : senderWxid);
            // 记录 AI 回复历史（含话题标签）
            if (topicAware) {
                aiReplyHistory.record(finalReply, currentTopic);
            }
            // 异步提取用户记忆
            try {
                List<String> contextTexts = contextMessages.stream()
                        .map(m -> (m.isSelf() ? "我" : m.getSenderNick()) + ": " + m.getContent())
                        .limit(5)
                        .collect(Collectors.toList());
                userMemoryExtractor.extractAndUpdate(senderWxid, senderNick, content, finalReply, contextTexts);
            } catch (Exception e) {
                log.warn("[Pipeline] 记忆提取失败: {}", e.getMessage());
            }
        } else {
            log.warn("[Pipeline] 发送失败: target={}, msg={}", sendTarget, resp.getMsg());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Pipeline] 完成 chatroom={}, topic={}, 耗时={}ms", chatroomId, currentTopic, elapsed);
    }
}
