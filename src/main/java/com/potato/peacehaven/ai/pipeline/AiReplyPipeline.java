package com.potato.peacehaven.ai.pipeline;

import com.potato.peacehaven.ai.decision.ReplyDecision;
import com.potato.peacehaven.ai.decision.ReplyDecisionService;
import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.memory.UserMemoryService;
import com.potato.peacehaven.ai.prompt.PromptBuilder;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService.RetrievedRecord;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService.ContextMessage;
import com.potato.peacehaven.ai.review.ReplyReviewService;
import com.potato.peacehaven.ai.review.ReviewResult;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.service.WechatApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
    private final WechatApiProperties wechatApiProps;
    private final ReplyDecisionService decisionService;
    private final ContextRetrievalService contextRetrievalService;
    private final ChatHistoryRetrievalService chatHistoryRetrievalService;
    private final UserMemoryService userMemoryService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ReplyReviewService reviewService;
    private final WechatApiService wechatApiService;

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
            log.debug("[Pipeline] AI 系统未就绪，跳过");
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

        // ===== 2. 并行拉取上下文（此处串行简化，CompletableFuture 可选优化） =====

        // 2a. 最近上下文
        List<ContextMessage> contextMessages = contextRetrievalService.getRecentContext(
                chatroomId, replyCfg.getContextSize());
        log.debug("[Pipeline] 拉取上下文 {} 条", contextMessages.size());

        // 2b. RAG 检索本人历史回复
        List<RetrievedRecord> ragRecords = Collections.emptyList();
        try {
            ragRecords = chatHistoryRetrievalService.retrieve(content, replyCfg.getRagTopK());
            log.debug("[Pipeline] RAG 检索 {} 条", ragRecords.size());
        } catch (Exception e) {
            log.warn("[Pipeline] RAG 检索失败，继续无 RAG: {}", e.getMessage());
        }

        // 2c. 用户记忆
        String userMemoryText = "";
        try {
            var memoryOpt = userMemoryService.getUserMemory(senderWxid);
            if (memoryOpt.isPresent()) {
                userMemoryText = userMemoryService.formatMemoryForPrompt(memoryOpt.get());
                log.debug("[Pipeline] 加载用户画像: {}", senderNick);
            }
        } catch (Exception e) {
            log.warn("[Pipeline] 用户记忆加载失败: {}", e.getMessage());
        }

        // ===== 3. 构建 Prompt =====
        List<LlmMessage> messages = promptBuilder.buildMessages(
                senderNick, content, userMemoryText, ragRecords, contextMessages);
        log.debug("[Pipeline] Prompt 构建完成，messages 数量={}", messages.size());

        // ===== 4. 调用 LLM =====
        AiProperties.LlmConfig llmCfg = aiProps.getLlm();
        String aiReply = llmClient.chat(messages, llmCfg.getTemperature(), llmCfg.getMaxTokens());
        if (aiReply == null || aiReply.isBlank()) {
            log.warn("[Pipeline] LLM 返回空，跳过发送");
            return;
        }
        log.info("[Pipeline] LLM 回复: {}", aiReply.length() > 100 ? aiReply.substring(0, 100) + "..." : aiReply);

        // ===== 5. 审核 =====
        ReviewResult review = reviewService.review(content, aiReply);
        if (!review.isApproved()) {
            log.info("[Pipeline] 审核未通过: {}", review.getReason());
            return;
        }
        String finalReply = review.getReply();

        // ===== 6. 模拟人类延迟（1-3s 随机） =====
        long delayMs = ThreadLocalRandom.current().nextLong(1000, 3001);
        log.debug("[Pipeline] 模拟延迟 {}ms", delayMs);
        Thread.sleep(delayMs);

        // ===== 7. 发送消息 =====
        String targetGroup = wechatApiProps.getGroupId();
        if (targetGroup == null || targetGroup.isBlank()) {
            targetGroup = chatroomId;
        }

        var resp = wechatApiService.sendText(targetGroup, finalReply);
        if (resp.isSuccess()) {
            log.info("[Pipeline] 发送成功 chatroom={}, reply={}", chatroomId,
                    finalReply.length() > 50 ? finalReply.substring(0, 50) + "..." : finalReply);
            // 更新决策统计
            decisionService.recordReply(chatroomId);
        } else {
            log.warn("[Pipeline] 发送失败: {}", resp.getMsg());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Pipeline] 完成 chatroom={}, 耗时={}ms", chatroomId, elapsed);
    }
}
