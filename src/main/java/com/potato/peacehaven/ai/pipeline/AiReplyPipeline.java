package com.potato.peacehaven.ai.pipeline;

import com.potato.peacehaven.ai.learning.ConfidenceTracker;
import com.potato.peacehaven.ai.persona.PersonaProfileService;
import com.potato.peacehaven.ai.decision.ReplyDecision;
import com.potato.peacehaven.ai.decision.ReplyDecisionService;
import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.llm.LlmReply;
import com.potato.peacehaven.ai.memory.UserMemoryExtractor;
import com.potato.peacehaven.ai.memory.UserMemoryService;
import com.potato.peacehaven.ai.persona.EffectivePersonaProfile;
import com.potato.peacehaven.ai.prompt.PromptBuilder;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService.RetrievedRecord;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService.ContextMessage;
import com.potato.peacehaven.ai.retrieval.MemoryRagService;
import com.potato.peacehaven.ai.retrieval.StyleFeature;
import com.potato.peacehaven.ai.retrieval.StyleTagger;
import com.potato.peacehaven.ai.review.ReplyReviewService;
import com.potato.peacehaven.ai.review.ReviewResult;
import com.potato.peacehaven.ai.summary.ConversationSummaryService;
import com.potato.peacehaven.ai.topic.*;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.config.TraceContext;
import com.potato.peacehaven.service.WechatApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
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
    private final ConfidenceTracker confidenceTracker;
    private final PersonaProfileService personaProfileService;
    private final StyleTagger styleTagger;
    private final ConversationProgressionService conversationProgressionService;

    /** 幽默场景检测正则 */
    private static final Pattern HUMOR_PATTERN = Pattern.compile(
            "哈{3,}|笑死|绷不住|666|6{4,}|离谱|绝了|神了|唐完"
    );

    /** 提问场景检测正则 */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "[?？]|怎么|为什么|如何|什么|是不是|能不能|可以吗"
    );

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
                                     boolean isMentioned, String traceId) {
        TraceContext.set(traceId);
        try {
            if (!aiProps.isReady()) {
                log.debug("[Pipeline] AI 系统未就绪（isReady=false），跳过处理");
                return;
            }
            doProcess(chatroomId, senderWxid, senderNick, content, isMentioned);
        } catch (Exception e) {
            log.error("[Pipeline] 处理异常 chatroom={}, sender={}", chatroomId, senderWxid, e);
        } finally {
            TraceContext.clear();
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
            log.debug("[Pipeline] 话题提取 topic={}, chatroom={}", currentTopic, chatroomId);
        }

        // ===== 3. RAG 必要性判断 =====
        boolean needsRag = topicJudgeService.needsRagLookup(content, chatroomId);
        log.debug("[Pipeline] RAG 判断 needsRag={}", needsRag);

        // ===== 4. 拉取最近上下文 + 生成摘要 =====
        List<ContextMessage> contextMessages = contextRetrievalService.getRecentContext(
                chatroomId, replyCfg.getContextSize());
        log.debug("[Pipeline] 拉取上下文 {} 条", contextMessages.size());

        // 格式化最近原始消息（保留 bot 回复，让 LLM 知道自己刚才说了什么）
        String recentRawMessages = contextRetrievalService.formatContextForPrompt(contextMessages);

        String conversationSummary = "";
        if (replyCfg.isUseConversationSummary()) {
            conversationSummary = summaryService.summarize(chatroomId, contextMessages);
            log.debug("[Pipeline] 对话摘要: {}",
                    conversationSummary.length() > 80 ? conversationSummary.substring(0, 80) + "..." : conversationSummary);
        }

        // ===== 5. Style RAG（条件：TopicJudge 通过） =====
        List<RetrievedRecord> ragRecords = Collections.emptyList();
        if (needsRag) {
            try {
                ragRecords = chatHistoryRetrievalService.retrieve(content, replyCfg.getRagTopK());
                log.debug("[Pipeline] Style RAG 检索 {} 条", ragRecords.size());
            } catch (Exception e) {
                log.warn("[Pipeline] Style RAG 检索失败，继续无 RAG: {}", e.getMessage());
            }
        } else {
            log.debug("[Pipeline] 跳过 Style RAG（无需检索）");
        }

        // ===== 6. Memory RAG（条件：senderWxid 有记忆） =====
        String memoryText = "";
        if (needsRag && senderWxid != null) {
            try {
                memoryText = memoryRagService.retrieveRelevantMemory(senderWxid, content);
                if (!memoryText.isBlank()) {
                    log.debug("[Pipeline] Memory RAG 命中: {}",
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

        // ===== 7. 检查话题过热（反锚定提示）+ 对话推进提示 =====
        String antiAnchoringHint = null;
        if (topicAware && currentTopic != null) {
            boolean convStale = conversationStateManager.isTopicStale(chatroomId);
            boolean historyStale = aiReplyHistory.isTopicOverused(currentTopic, replyCfg.getTopicStaleThreshold());
            if (convStale || historyStale) {
                antiAnchoringHint = aiProps.getPrompt().getAntiAnchoringHint();
                log.debug("[Pipeline] 话题过热，注入反锚定提示 convStale={}, historyStale={}, topic={}",
                        convStale, historyStale, currentTopic);
            }
        }

        // 对话推进提示（bot 回复重复时注入，引导改变立场）
        String progressionHint = conversationProgressionService.getProgressionHint(chatroomId);
        if (progressionHint != null) {
            log.debug("[Pipeline] 注入对话推进提示 chatroom={}, hint={}",
                    chatroomId, progressionHint.length() > 60 ? progressionHint.substring(0, 60) + "..." : progressionHint);
        }

        // ===== 8. 构建 Prompt =====
        List<LlmMessage> messages = promptBuilder.buildMessages(
                senderNick, content, conversationSummary, recentRawMessages, memoryText, ragRecords, antiAnchoringHint, progressionHint);
        boolean jsonMode = aiProps.getPrompt().isJsonReplyFormat();
        log.debug("[Pipeline] Prompt 构建完成 msgs={}, persona={}, version={}, jsonMode={}",
                messages.size(), aiProps.getPrompt().getPersonaName(),
                com.potato.peacehaven.ai.prompt.PromptBuilder.PROMPT_VERSION, jsonMode);

        // ===== 9. 调用 LLM（动态 temperature）=====
        AiProperties.LlmConfig llmCfg = aiProps.getLlm();
        String scene = detectScene(content);
        Double temperature = aiProps.resolveTemperature(scene);
        String rawReply = llmClient.chat(messages, temperature, llmCfg.getMaxTokens());
        if (scene != null) {
            log.debug("[Pipeline] 场景检测: scene={}, temperature={}", scene, temperature);
        }
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
                log.info("[Pipeline] LLM 结构化输出: confidence={}, reason={}, reply={}",
                        String.format("%.2f", parsed.getConfidence()),
                        parsed.getReplyReason(),
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

        // ===== 10b. Persona Match 评分 + Expression Fatigue 追踪 =====
        try {
            EffectivePersonaProfile personaProfile = personaProfileService.resolve(senderNick, chatroomId);
            StyleFeature replyFeature = styleTagger.analyze(finalReply);
            double llmConfidence = jsonMode && rawReply != null ? parseConfidence(rawReply) : 0.5;
            double personaMatch = confidenceTracker.computePersonaMatch(
                    finalReply, replyFeature,
                    personaProfile.getHumorScore(), personaProfile.getSarcasmScore(),
                    personaProfile.getWarmthScore(), personaProfile.getFormalScore(),
                    llmConfidence);
            confidenceTracker.recordReply(llmConfidence, personaMatch);
            log.debug("[Pipeline] personaMatch={}, llmConf={}",
                    String.format("%.2f", personaMatch), String.format("%.2f", llmConfidence));
        } catch (Exception e) {
            log.warn("[Pipeline] personaMatch 计算失败: {}", e.getMessage());
        }

        // ===== 11. 模拟人类延迟（1-3s 随机） =====
        long delayMs = ThreadLocalRandom.current().nextLong(1000, 3001);
        log.debug("[Pipeline] 模拟人类延迟 {}ms", delayMs);
        Thread.sleep(delayMs);

        // ===== 12. 拆分并发送消息（按换行拆分，逐条发送） =====
        String sendTarget = (chatroomId != null && !chatroomId.isBlank()) ? chatroomId : senderWxid;
        boolean isGroupChat = chatroomId != null && !chatroomId.isBlank();

        // 按换行拆分为多条消息气泡（过滤空行）
        String[] bubbleArr = finalReply.split("\\n+");
        List<String> bubbleList = new java.util.ArrayList<>();
        for (String b : bubbleArr) {
            String trimmed = b.trim();
            if (!trimmed.isEmpty()) bubbleList.add(trimmed);
        }
        if (bubbleList.isEmpty()) {
            log.warn("[Pipeline] 拆分后无有效消息，跳过发送");
            return;
        }

        boolean sendSuccess = true;
        for (int i = 0; i < bubbleList.size(); i++) {
            String msg = bubbleList.get(i);

            // 第一条消息用初始延迟，后续消息间加短延迟（0.5-1.5s）模拟逐条打字
            if (i > 0) {
                long bubbleDelay = ThreadLocalRandom.current().nextLong(500, 1501);
                Thread.sleep(bubbleDelay);
            }

            var resp = wechatApiService.sendText(sendTarget, msg);
            if (resp.isSuccess()) {
                log.info("[Pipeline] 发送成功 [{}/{}] target={}, msg={}",
                        i + 1, bubbleList.size(), sendTarget,
                        msg.length() > 50 ? msg.substring(0, 50) + "..." : msg);
                // 注册 AI 回复指纹（每条都注册）
                aiReplyTracker.register(msg);
            } else {
                log.warn("[Pipeline] 发送失败 [{}/{}] target={}, msg={}",
                        i + 1, bubbleList.size(), sendTarget, resp.getMsg());
                sendSuccess = false;
                break; // 发送失败则停止后续消息
            }
        }

        if (sendSuccess) {
            // 持久化 bot 回复到聊天记录（下次上下文拉取时可见，保持话题延续性）
            if (isGroupChat) {
                contextRetrievalService.saveBotReply(sendTarget, null, finalReply);
            }

            // 对话推进追踪：记录 bot 回复 + 行为分类 + 更新推进分数
            try {
                conversationProgressionService.analyzeAndUpdateProgression(
                        chatroomId != null ? chatroomId : senderWxid, finalReply, content);
            } catch (Exception e) {
                log.warn("[Pipeline] 对话推进追踪失败: {}", e.getMessage());
            }

            // 更新决策统计
            decisionService.recordReply(chatroomId != null ? chatroomId : senderWxid);
            // 记录 AI 回复历史（含话题标签，用完整回复）
            if (topicAware) {
                aiReplyHistory.record(finalReply, currentTopic);
            }
            // 追踪 expression fatigue
            try {
                confidenceTracker.trackExpressionUsage(finalReply);
            } catch (Exception e) {
                log.warn("[Pipeline] expression fatigue 追踪失败: {}", e.getMessage());
            }
            // 异步提取用户记忆（只保留目标用户自己的消息，防止别人的话被归入此人画像）
            try {
                List<String> contextTexts = contextMessages.stream()
                        .filter(m -> !m.isSelf() && !m.isBotReply()
                                && senderWxid.equals(m.getSenderWxid()))
                        .map(m -> m.getSenderNick() + ": " + m.getContent())
                        .limit(5)
                        .collect(Collectors.toList());
                userMemoryExtractor.extractAndUpdate(senderWxid, senderNick, content, null, contextTexts);
            } catch (Exception e) {
                log.warn("[Pipeline] 记忆提取失败: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Pipeline] 完成 chatroom={}, topic={}, 耗时={}ms", chatroomId, currentTopic, elapsed);
    }

    /**
     * 检测消息场景类型，用于动态调整 temperature
     *
     * @param content 当前消息内容
     * @return 场景类型：humor / question / normal / null（无法判断）
     */
    private String detectScene(String content) {
        if (content == null || content.isBlank()) return null;
        if (HUMOR_PATTERN.matcher(content).find()) return "humor";
        if (QUESTION_PATTERN.matcher(content).find()) return "question";
        return "normal";
    }

    /**
     * 从 LLM 原始回复中解析 confidence 值（JSON 模式）
     */
    private double parseConfidence(String rawReply) {
        try {
            LlmReply parsed = LlmReply.parse(rawReply);
            if (parsed != null) return parsed.getConfidence();
        } catch (Exception ignored) {}
        return 0.5;
    }
}
