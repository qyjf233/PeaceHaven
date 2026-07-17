package com.potato.peacehaven.ai.learning;

import com.potato.peacehaven.ai.llm.LlmClient;
import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.retrieval.StyleAggregator;
import com.potato.peacehaven.ai.retrieval.StyleAggregator.AggregatedStyle;
import com.potato.peacehaven.ai.retrieval.StyleFeature;
import com.potato.peacehaven.ai.retrieval.StyleTagger;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 定时学习服务（Persona Engine v4.1 — Phase 4 Task 10）
 * <p>
 * 每 6 小时从 bot_chat_record 中拉取本人真实消息，执行 13 步学习流程：
 * <ol>
 *   <li>查最近 200 条 is_self=true AND is_bot_reply=false</li>
 *   <li>Hash 检查：消息 hash vs styleSourceHash，相同跳过</li>
 *   <li>按 Scene(roomId) + Person(senderNick of counterpart) 分组</li>
 *   <li>全局 StyleFeature 统计（含 variance）</li>
 *   <li>多维 confidence（含 distributionFactor）</li>
 *   <li>ExpressionProfile + ExpressionSceneUsage 更新</li>
 *   <li>CurrentStateProfile 更新（stateVersion 每天最多+1）</li>
 *   <li>SceneProfile + RelationshipProfile 更新</li>
 *   <li>DriftDetector：global vs scene change 判断</li>
 *   <li>Stability 正则化融合</li>
 *   <li>LLM 风格提炼（hash 变化时）</li>
 *   <li>双版本判断 + Snapshot</li>
 *   <li>持久化</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StyleLearningService {

    private final BotChatRecordRepository chatRecordRepo;
    private final LearnedStyleConfigRepository learnedStyleRepo;
    private final ExpressionProfileRepository expressionRepo;
    private final ExpressionSceneUsageRepository expressionSceneRepo;
    private final RelationshipProfileRepository relationshipRepo;
    private final SceneProfileRepository sceneRepo;
    private final CurrentStateProfileRepository currentStateRepo;
    private final PersonaStabilityRepository stabilityRepo;
    private final PersonaStyleSnapshotRepository snapshotRepo;
    private final StyleTagger styleTagger;
    private final StyleAggregator styleAggregator;
    private final PersonaDriftDetector driftDetector;
    private final LlmClient llmClient;
    private final AiProperties aiProps;

    // ===== Bootstrap 检查 =====

    /**
     * 检查是否处于 Bootstrap 阶段（前 N 条只采集不改 persona）
     */
    public boolean isInBootstrapPhase() {
        AiProperties.BootstrapConfig bootstrap = aiProps.getLearning().getBootstrap();
        if (!bootstrap.isEnabled()) return false;
        long totalSelfMessages = chatRecordRepo.countByIsSelfTrueAndIsBotReplyFalse();
        return totalSelfMessages < bootstrap.getSamples();
    }

    // ===== 定时触发 =====

    /**
     * 定时学习任务（每 6 小时）
     */
    @Scheduled(fixedDelayString = "#{${ai.learning.interval-hours:6} * 3600000}",
               initialDelay = 60000)
    @Transactional
    public void learn() {
        if (!aiProps.getLearning().isEnabled()) {
            return;
        }

        log.info("[Learning] 定时学习触发");

        // Step 1: 查最近 maxSamples 条本人消息
        int maxSamples = aiProps.getLearning().getMaxSamples();
        List<BotChatRecord> selfMessages = chatRecordRepo
                .findByIsSelfTrueAndIsBotReplyFalseOrderByCreatedAtDesc(PageRequest.of(0, maxSamples));

        if (selfMessages.isEmpty()) {
            log.debug("[Learning] 无本人消息，跳过");
            return;
        }

        int minSamples = aiProps.getLearning().getMinSamples();
        if (selfMessages.size() < minSamples) {
            log.debug("[Learning] 本人消息不足 {}/{}，跳过", selfMessages.size(), minSamples);
            return;
        }

        // Bootstrap 检查
        boolean bootstrapMode = isInBootstrapPhase();
        if (bootstrapMode) {
            log.debug("[Learning] Bootstrap 阶段：只采集不更新 persona");
        }

        // Step 2: Hash 检查
        String currentHash = computeHash(selfMessages);
        LearnedStyleConfig config = learnedStyleRepo.findById(1L).orElse(
                LearnedStyleConfig.builder().id(1L).build());

        boolean hashChanged = !currentHash.equals(config.getStyleSourceHash());
        if (!hashChanged) {
            log.debug("[Learning] 消息 hash 未变化，跳过 LLM 提炼");
        }

        // Step 3: 按 Scene(roomId) + Person 分组
        Map<String, List<BotChatRecord>> byRoom = selfMessages.stream()
                .collect(Collectors.groupingBy(r -> r.getRoomId() != null ? r.getRoomId() : "private"));

        // 确定场景类型（简化映射：根据 roomId 出现频率推断）
        Map<String, String> roomSceneType = inferSceneTypes(byRoom);

        // Step 4: 全局 StyleFeature 统计
        List<StyleFeature> allFeatures = new ArrayList<>();
        Map<String, List<StyleFeature>> featuresByRoom = new LinkedHashMap<>();

        for (var entry : byRoom.entrySet()) {
            String roomId = entry.getKey();
            List<StyleFeature> roomFeatures = entry.getValue().stream()
                    .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                    .map(r -> styleTagger.analyze(r.getContent()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            featuresByRoom.put(roomId, roomFeatures);
            allFeatures.addAll(roomFeatures);
        }

        if (allFeatures.isEmpty()) {
            log.debug("[Learning] 无有效特征，跳过");
            return;
        }

        AggregatedStyle globalStyle = styleAggregator.aggregate(allFeatures);
        log.debug("[Learning] 全局聚合: samples={}, lengthAvg={}, formal={}, slang={}, lengthVar={}",
                globalStyle.getSampleCount(),
                globalStyle.getLengthAvg(),
                fmt(globalStyle.getFormalAvg()),
                fmt(globalStyle.getSlangAvg()),
                fmt(globalStyle.getLengthVariance()));

        // Step 5: 多维 confidence
        double sampleFactor = Math.min((double) allFeatures.size() / 200, 1.0);
        double timeSpanFactor = computeTimeSpanFactor(selfMessages);
        double timeFactor = 0.5 + 0.5 * timeSpanFactor;
        // 按 distinct room 计数（每个群 = 不同社交上下文），不再按推断的 sceneType
        int distinctRooms = (int) byRoom.keySet().stream().filter(k -> !"private".equals(k)).count();
        double sceneFactor = Math.min((double) distinctRooms / 3.0, 1.0);
        double distributionFactor = computeDistributionFactor(selfMessages);
        double confidence = sampleFactor * timeFactor * sceneFactor * distributionFactor;

        log.debug("[Learning] confidence={}: sample={}, time={}, scene={}, distribution={}",
                fmt(confidence), fmt(sampleFactor), fmt(timeFactor), fmt(sceneFactor), fmt(distributionFactor));

        // Step 6: ExpressionProfile 更新（提取高频特色表达）
        if (!bootstrapMode) {
            updateExpressionProfiles(selfMessages, roomSceneType);
        }

        // Step 7: CurrentStateProfile 更新（每天最多+1）
        updateCurrentState(selfMessages);

        // Step 8: SceneProfile + RelationshipProfile 更新
        if (!bootstrapMode) {
            updateSceneProfiles(featuresByRoom, roomSceneType);
            updateRelationshipProfiles(selfMessages, featuresByRoom, byRoom);
        }

        // Step 9: DriftDetector（保留，基于历史快照对比）
        if (!bootstrapMode && hashChanged) {
            driftDetector.detectAndUpdateStability(
                    config.getHumorScore(),
                    config.getSarcasmScore(),
                    config.getWarmthScore());
        }

        // Step 10: 客观统计融合（不再 blend 主观 persona 分数）
        if (!bootstrapMode) {
            config.setFormalScore(globalStyle.getFormalAvg());
            config.setSlangScore(globalStyle.getSlangAvg());
            config.setAvgLength(globalStyle.getLengthAvg());
            config.setLengthVariance(globalStyle.getLengthVariance());
            config.setExpressionVariance(globalStyle.getExpressionVariance());
        }

        // Step 11: LLM Observation 生成（hash 变化时，核心改造）
        if (hashChanged && !bootstrapMode) {
            String observation = generatePersonaObservation(selfMessages, globalStyle);
            config.setPersonaObservation(observation);
            // styleDescription 保留作为兜底（observation 已是核心）
            String styleDescription = extractStyleDescription(selfMessages, globalStyle);
            config.setStyleDescription(styleDescription);
        }

        // Step 12: 版本判断 + Snapshot
        // personaChanged：observation 有变化即认为人格变化
        boolean personaChanged = hashChanged && !bootstrapMode &&
                config.getPersonaObservation() != null;

        if (hashChanged && !bootstrapMode) {
            config.setStyleVersion(config.getStyleVersion() + 1);
        }
        if (personaChanged) {
            config.setPersonaVersion(config.getPersonaVersion() + 1);
        }

        // Step 13: 持久化
        config.setLearningConfidence(confidence);
        config.setSampleFactor(sampleFactor);
        config.setTimeSpanFactor(timeSpanFactor);
        config.setSceneFactor(sceneFactor);
        config.setDistributionFactor(distributionFactor);
        config.setSampleCount(allFeatures.size());
        config.setSceneCount(distinctRooms);
        config.setStyleSourceHash(currentHash);
        learnedStyleRepo.save(config);

        // 保存快照
        if (hashChanged) {
            PersonaStyleSnapshot snapshot = PersonaStyleSnapshot.builder()
                    .styleVersion(config.getStyleVersion())
                    .personaVersion(config.getPersonaVersion())
                    .humorScore(config.getHumorScore())
                    .sarcasmScore(config.getSarcasmScore())
                    .casualScore(config.getCasualScore())
                    .warmthScore(config.getWarmthScore())
                    .formalScore(config.getFormalScore())
                    .styleDescription(config.getStyleDescription())
                    .learningConfidence(confidence)
                    .sampleCount(allFeatures.size())
                    .sceneCount(distinctRooms)
                    .trigger(bootstrapMode ? "bootstrap_collect" : "new_" + allFeatures.size() + "_messages")
                    .build();
            snapshotRepo.save(snapshot);
        }

        log.info("[Learning] 学习完成: hash={}, bootstrap={}, confidence={}, samples={}, scenes={}, personaV={}, styleV={}",
                currentHash.substring(0, 8), bootstrapMode, fmt(confidence),
                allFeatures.size(), distinctRooms,
                config.getPersonaVersion(), config.getStyleVersion());
    }

    // ===== 内部方法 =====

    /**
     * 计算消息列表的 hash（用于判断是否有新数据）
     */
    private String computeHash(List<BotChatRecord> messages) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (BotChatRecord r : messages) {
                md.update(String.valueOf(r.getId()).getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 推断场景类型映射（roomId → sceneType）
     * <p>简化规则：根据群名/roomId 特征推断</p>
     */
    private Map<String, String> inferSceneTypes(Map<String, List<BotChatRecord>> byRoom) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : byRoom.entrySet()) {
            String roomId = entry.getKey();
            List<BotChatRecord> records = entry.getValue();

            String sceneType;
            if ("private".equals(roomId)) {
                sceneType = "private";
            } else {
                // 根据群名关键词推断（简化版，后续可接入 AI 分类）
                String roomName = records.get(0).getRoomName();
                if (roomName != null) {
                    String name = roomName.toLowerCase();
                    if (name.contains("工作") || name.contains("work") || name.contains("项目")) {
                        sceneType = "work_chat";
                    } else if (name.contains("家") || name.contains("family")) {
                        sceneType = "family";
                    } else {
                        sceneType = "friend_group";
                    }
                } else {
                    sceneType = "friend_group"; // 默认
                }
            }
            result.put(roomId, sceneType);
        }
        return result;
    }

    /**
     * 计算时间跨度因子（渐进式）
     * <p>
     * 旧逻辑：days/90（同一天=0，导致 confidence 极低）
     * 新逻辑：base 0.3 + 渐进奖励，确保同一天也有基本置信度
     * <ul>
     *   <li>0 天 → 0.30</li>
     *   <li>3 天 → 0.51</li>
     *   <li>7 天 → 0.65</li>
     *   <li>30 天 → 1.00</li>
     * </ul>
     */
    private double computeTimeSpanFactor(List<BotChatRecord> messages) {
        if (messages.size() < 2) return 0.3;
        LocalDateTime oldest = messages.get(messages.size() - 1).getCreatedAt();
        LocalDateTime newest = messages.get(0).getCreatedAt();
        if (oldest == null || newest == null) return 0.5;
        long days = Duration.between(oldest, newest).toDays();
        return 0.3 + 0.7 * Math.min((double) days / 30.0, 1.0);
    }

    /**
     * 计算消息天数分布均匀度
     * <p>1天爆聊200条→低, 90天每天2条→高</p>
     */
    private double computeDistributionFactor(List<BotChatRecord> messages) {
        if (messages.size() < 2) return 0.5;

        // 统计每天的消息数
        Map<LocalDate, Long> dailyCounts = messages.stream()
                .filter(r -> r.getCreatedAt() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getCreatedAt().toLocalDate(),
                        Collectors.counting()));

        if (dailyCounts.size() < 2) return 0.2; // 集中在1天

        // 计算变异系数（CV = stddev / mean），CV 越小越均匀
        double mean = dailyCounts.values().stream().mapToLong(Long::longValue).average().orElse(1);
        double variance = dailyCounts.values().stream()
                .mapToDouble(c -> Math.pow(c - mean, 2))
                .average().orElse(0);
        double cv = Math.sqrt(variance) / Math.max(mean, 1);

        // CV 低 → 分布均匀 → factor 高
        // 典型值：CV=0 (完美均匀)→1.0, CV=1 (标准差=均值)→0.5, CV=2→0.2
        return Math.max(0.1, 1.0 / (1.0 + cv));
    }

    /**
     * 更新 ExpressionProfile（提取特色表达）
     */
    private void updateExpressionProfiles(List<BotChatRecord> messages, Map<String, String> roomSceneType) {
        // 统计每个短语出现次数
        Map<String, Integer> phraseCounts = new LinkedHashMap<>();
        Map<String, Set<String>> phraseScenes = new LinkedHashMap<>();

        for (BotChatRecord r : messages) {
            if (r.getContent() == null || r.getContent().isBlank()) continue;
            String sceneType = roomSceneType.getOrDefault(
                    r.getRoomId() != null ? r.getRoomId() : "private", "friend_group");

            // 提取短表达（3-10字的短语，排除常见停用词）
            List<String> phrases = extractCatchphrases(r.getContent());
            for (String phrase : phrases) {
                phraseCounts.merge(phrase, 1, Integer::sum);
                phraseScenes.computeIfAbsent(phrase, k -> new HashSet<>()).add(sceneType);
            }
        }

        // 高频表达 → ExpressionProfile
        double total = messages.size();
        for (var entry : phraseCounts.entrySet()) {
            String phrase = entry.getKey();
            int count = entry.getValue();
            double freq = count / total;

            if (freq < 0.02 || count < 2) continue; // 太低频忽略

            ExpressionProfile profile = expressionRepo.findByPhrase(phrase)
                    .orElse(ExpressionProfile.builder()
                            .phrase(phrase)
                            .fatigueScore(0)
                            .consecutiveUsed(0)
                            .build());

            // 加权更新频率
            profile.setFrequency(0.7 * profile.getFrequency() + 0.3 * freq);
            profile.setConfidence(Math.min((double) count / 10, 1.0));

            // 推断场景
            Set<String> scenes = phraseScenes.getOrDefault(phrase, Set.of());
            String dominantScene = scenes.stream().findFirst().orElse("friend");
            profile.setAllowedScene(dominantScene);

            expressionRepo.save(profile);

            // ExpressionSceneUsage
            for (String sceneType : new HashSet<>(List.of("friend_group", "work_chat", "family", "private", "stranger"))) {
                ExpressionSceneUsage usage = expressionSceneRepo
                        .findByExpressionIdAndSceneType(profile.getId(), sceneType)
                        .orElse(ExpressionSceneUsage.builder()
                                .expressionId(profile.getId())
                                .sceneType(sceneType)
                                .usageCount(0)
                                .build());
                if (scenes.contains(sceneType)) {
                    // 统计此场景中的出现次数
                    long sceneCount = messages.stream()
                            .filter(r -> {
                                String st = roomSceneType.getOrDefault(
                                        r.getRoomId() != null ? r.getRoomId() : "private", "friend_group");
                                return sceneType.equals(st) && r.getContent() != null && r.getContent().contains(phrase);
                            })
                            .count();
                    usage.setUsageCount(usage.getUsageCount() + (int) sceneCount);
                }
                expressionSceneRepo.save(usage);
            }
        }
    }

    /**
     * 从消息内容中提取候选特色短语（3-10字）
     */
    private List<String> extractCatchphrases(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.length() < 3) return result;

        // 按标点分割
        String[] segments = content.split("[,，.。!！?？;；~、\\s]+");
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.length() >= 2 && trimmed.length() <= 10) {
                // 排除纯数字、纯英文、常见停用词
                if (!trimmed.matches("^\\d+$") && !trimmed.matches("^[a-zA-Z\\s]+$")) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * 更新 CurrentStateProfile（每天最多+1 stateVersion）
     */
    private void updateCurrentState(List<BotChatRecord> messages) {
        CurrentStateProfile state = currentStateRepo.findById(1L).orElse(
                CurrentStateProfile.builder().id(1L).stateVersion(0).build());

        // 近7天消息数
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long recentCount = messages.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo))
                .count();
        state.setMessageCount7d((int) recentCount);

        // 推断 energy（消息活跃度）
        double activityRatio = Math.min((double) recentCount / 50, 1.0);
        state.setEnergy(0.3 + 0.7 * activityRatio);

        // 推断 stress（短消息多+高频率 → 可能压力大）
        double shortMsgRatio = messages.stream()
                .filter(r -> r.getContent() != null && r.getContent().length() < 10)
                .count() / (double) Math.max(messages.size(), 1);
        state.setStress(shortMsgRatio * 0.5 + activityRatio * 0.3);

        // 社交模式
        long distinctRooms = messages.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo))
                .map(r -> r.getRoomId() != null ? r.getRoomId() : "private")
                .distinct().count();
        state.setSocialMode(Math.min((double) distinctRooms / 5, 1.0));

        // stateVersion 每天最多+1
        LocalDate today = LocalDate.now();
        if (state.getUpdatedAt() == null || state.getUpdatedAt().toLocalDate().isBefore(today)) {
            state.setStateVersion(state.getStateVersion() + 1);
        }

        currentStateRepo.save(state);
    }

    /**
     * 更新 SceneProfile（per-room 画像）
     */
    private void updateSceneProfiles(Map<String, List<StyleFeature>> featuresByRoom,
                                      Map<String, String> roomSceneType) {
        // 按 sceneType 聚合
        Map<String, List<StyleFeature>> bySceneType = new LinkedHashMap<>();
        for (var entry : featuresByRoom.entrySet()) {
            String sceneType = roomSceneType.getOrDefault(entry.getKey(), "friend_group");
            bySceneType.computeIfAbsent(sceneType, k -> new ArrayList<>()).addAll(entry.getValue());
        }

        for (var entry : bySceneType.entrySet()) {
            String sceneType = entry.getKey();
            AggregatedStyle sceneStyle = styleAggregator.aggregate(entry.getValue());
            if (sceneStyle == null) continue;

            SceneProfile profile = sceneRepo.findBySceneType(sceneType)
                    .orElse(SceneProfile.builder().sceneType(sceneType).build());

            // 只更新客观统计维度（formal/slang），主观 persona 分数保留旧值不更新
            profile.setFormalScore(sceneStyle.getFormalAvg());
            profile.setSampleCount(profile.getSampleCount() + sceneStyle.getSampleCount());

            sceneRepo.save(profile);
            log.debug("[Learning] SceneProfile {}: formal={}, slang={}, samples={}",
                    sceneType, fmt(profile.getFormalScore()),
                    fmt(sceneStyle.getSlangAvg()), profile.getSampleCount());
        }
    }

    /**
     * 更新 RelationshipProfile（per-person 画像）
     * <p>简化版：根据对话上下文中对方消息推断，此处基于 roomId 统计</p>
     */
    private void updateRelationshipProfiles(List<BotChatRecord> selfMessages,
                                             Map<String, List<StyleFeature>> featuresByRoom,
                                             Map<String, List<BotChatRecord>> byRoom) {
        // 简化：对每个 roomId，用本人消息客观特征 + 群名推断关系
        for (var entry : byRoom.entrySet()) {
            String roomId = entry.getKey();
            if ("private".equals(roomId)) continue;

            List<StyleFeature> roomFeatures = featuresByRoom.get(roomId);
            if (roomFeatures == null || roomFeatures.isEmpty()) continue;

            AggregatedStyle roomStyle = styleAggregator.aggregate(roomFeatures);
            if (roomStyle == null) continue;

            String contactName = roomId;
            List<BotChatRecord> roomRecords = entry.getValue();
            if (!roomRecords.isEmpty() && roomRecords.get(0).getRoomName() != null
                    && !roomRecords.get(0).getRoomName().equals(roomId)) {
                contactName = roomRecords.get(0).getRoomName();
            }
            final String finalContactName = contactName;

            // 查找 profile：优先按 contactName，也尝试按 roomId 查旧数据
            RelationshipProfile profile = relationshipRepo.findByContactName(finalContactName)
                    .orElseGet(() -> {
                        // 如果 contactName 是群名（非 roomId），检查是否有旧的 roomId-based profile
                        if (!finalContactName.equals(roomId)) {
                            var oldProfile = relationshipRepo.findByContactName(roomId);
                            if (oldProfile.isPresent()) {
                                RelationshipProfile p = oldProfile.get();
                                p.setContactName(finalContactName); // 迁移 contactName
                                log.info("[Learning] 迁移 relationship contactName: {} → {}", roomId, finalContactName);
                                return p;
                            }
                        }
                        return RelationshipProfile.builder()
                                .contactName(finalContactName)
                                .relationshipType("group")
                                .intimacyLevel(5)
                                .build();
                    });

            // 只更新客观统计维度，主观 persona 分数保留旧值
            profile.setFormalScore(roomStyle.getFormalAvg());
            profile.setSampleCount(profile.getSampleCount() + roomStyle.getSampleCount());

            // 推断 communicationStyle（基于客观特征）
            if (roomStyle.getFormalAvg() > 0.5) {
                profile.setCommunicationStyle("正式交流");
            } else if (roomStyle.getSlangAvg() > 0.4) {
                profile.setCommunicationStyle("自然随意");
            } else {
                profile.setCommunicationStyle("自然交流");
            }

            relationshipRepo.save(profile);
        }
    }

    // ========================================================================
    //  LLM Observation 生成（核心新方法）
    // ========================================================================

    /**
     * LLM 生成 Persona Observation（核心方法，替代旧的正则打分）
     * <p>
     * 从 200 条消息中抽样 30-40 条（分层抽样），连同客观统计数据发给 LLM，
     * 输出结构化观察文本，直接注入 Prompt。
     * </p>
     */
    private String generatePersonaObservation(List<BotChatRecord> messages, AggregatedStyle stats) {
        try {
            // 分层抽样：按 roomId 比例分配，最多取 35 条
            List<String> sampledMessages = sampleMessages(messages, 35);

            String prompt = buildObservationPrompt(sampledMessages, stats);
            List<LlmMessage> llmMessages = List.of(
                    LlmMessage.system("""
                            你是一个聊天风格分析专家。你的任务是观察一个人的真实聊天消息，总结出他的交流风格和行为模式。

                            输出要求：
                            1. 用观察性语言，不要使用"幽默""吐槽""温暖"等主观评价词
                            2. 描述行为规律，不是给分数
                            3. 分三个层次：通常/偶尔/几乎没有
                            4. 每条观察简短一行
                            5. 总共 8-12 条观察

                            输出格式：
                            通常：
                            - （高频行为特征）

                            偶尔：
                            - （中频行为特征）

                            几乎没有：
                            - （极低频或不出现的行为）
                            """),
                    LlmMessage.user(prompt)
            );

            String result = llmClient.chat(llmMessages, 0.3, 500);
            if (result != null && !result.isBlank()) {
                log.info("[Learning] Persona Observation 生成成功 ({} chars)", result.length());
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("[Learning] Persona Observation 生成失败: {}", e.getMessage());
        }

        // Fallback：基于客观统计生成简单观察
        return generateFallbackObservation(stats);
    }

    /**
     * 分层抽样消息（按 roomId 比例分配）
     */
    private List<String> sampleMessages(List<BotChatRecord> messages, int maxCount) {
        if (messages.size() <= maxCount) {
            return messages.stream()
                    .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                    .map(BotChatRecord::getContent)
                    .collect(Collectors.toList());
        }

        // 按 roomId 分组
        Map<String, List<BotChatRecord>> byRoom = messages.stream()
                .filter(r -> r.getContent() != null && !r.getContent().isBlank())
                .collect(Collectors.groupingBy(r -> r.getRoomId() != null ? r.getRoomId() : "private"));

        List<String> result = new ArrayList<>();
        double ratio = (double) maxCount / messages.size();

        for (var entry : byRoom.entrySet()) {
            List<BotChatRecord> roomMsgs = entry.getValue();
            int take = Math.max(1, (int) (roomMsgs.size() * ratio));

            // 随机打乱后取前 take 条
            List<BotChatRecord> shuffled = new ArrayList<>(roomMsgs);
            Collections.shuffle(shuffled);

            for (int i = 0; i < Math.min(take, shuffled.size()); i++) {
                result.add(shuffled.get(i).getContent());
            }
        }

        // 如果抽样超过 maxCount，截断
        if (result.size() > maxCount) {
            Collections.shuffle(result);
            result = result.subList(0, maxCount);
        }

        return result;
    }

    /**
     * 构建 Observation 生成的 LLM Prompt
     */
    private String buildObservationPrompt(List<String> sampledMessages, AggregatedStyle stats) {
        StringBuilder sb = new StringBuilder();

        // 客观统计
        sb.append("## 客观统计数据\n");
        sb.append(String.format("- 消息总数：%d 条\n", stats.getSampleCount()));
        sb.append(String.format("- 平均长度：%d 字\n", stats.getLengthAvg()));
        sb.append(String.format("- 长度波动：%s\n", stats.getLengthVariance() > 500 ? "较大（长短不一）" : "较小（长度稳定）"));
        sb.append(String.format("- 正式词密度：%.2f\n", stats.getFormalAvg()));
        sb.append(String.format("- 俚语/网络用语密度：%.2f\n", stats.getSlangAvg()));
        sb.append(String.format("- Emoji 使用密度：%.3f\n", stats.getEmojiAvg()));
        sb.append(String.format("- 标点密度：%.3f\n", stats.getPunctuationAvg()));
        sb.append("\n");

        // 抽样消息
        sb.append("## 真实聊天消息样本\n");
        for (int i = 0; i < sampledMessages.size(); i++) {
            String msg = sampledMessages.get(i);
            // 截断过长消息
            if (msg.length() > 200) {
                msg = msg.substring(0, 200) + "...";
            }
            sb.append(String.format("%d. %s\n", i + 1, msg));
        }
        sb.append("\n请根据以上统计和消息样本，输出这个人的交流风格观察。");

        return sb.toString();
    }

    /**
     * Fallback：基于客观统计生成简单观察（LLM 失败时兜底）
     */
    private String generateFallbackObservation(AggregatedStyle stats) {
        StringBuilder sb = new StringBuilder();

        sb.append("通常：\n");
        if (stats.getLengthAvg() < 30) {
            sb.append("- 回复很短，一两句话结束\n");
        } else if (stats.getLengthAvg() < 80) {
            sb.append("- 回复长度中等\n");
        } else {
            sb.append("- 回复通常比较长\n");
        }

        if (stats.getFormalAvg() < 0.2) {
            sb.append("- 几乎不用书面表达\n");
        } else if (stats.getFormalAvg() > 0.4) {
            sb.append("- 偶尔使用正式表达\n");
        }

        if (stats.getSlangAvg() > 0.3) {
            sb.append("- 经常使用网络用语\n");
        }

        if (stats.getLengthVariance() > 500) {
            sb.append("- 表达长度波动较大，多数很短，偶尔长篇\n");
        }

        sb.append("\n偶尔：\n");
        sb.append("- 使用口头禅或特色表达\n");

        sb.append("\n几乎没有：\n");
        sb.append("- 长篇分析或系统性输出\n");

        return sb.toString().trim();
    }

    // ========================================================================
    //  风格描述（保留作为兜底，observation 已是核心）
    // ========================================================================

    /**
     * LLM 风格描述提炼（简化版，作为 styleDescription 的兜底）
     */
    private String extractStyleDescription(List<BotChatRecord> messages, AggregatedStyle style) {
        try {
            String prompt = String.format("""
                    聊天统计数据：
                    - 消息数：%d
                    - 平均长度：%d 字，长度波动：%s
                    - 正式表达占比：%s
                    - 网络用语密度：%s

                    请用观察性语言描述这个人的说话风格，3-5 行简短描述。
                    不要使用'幽默''吐槽''调侃'等词，用'轻松''随意''简短'等自然词汇。
                    """,
                    style.getSampleCount(),
                    style.getLengthAvg(),
                    style.getLengthVariance() > 500 ? "较大" : "较小",
                    fmt(style.getFormalAvg()),
                    fmt(style.getSlangAvg()));

            List<LlmMessage> llmMessages = List.of(
                    LlmMessage.system("你是一个语言风格分析专家。"),
                    LlmMessage.user(prompt)
            );
            String result = llmClient.chat(llmMessages, 0.3, 300);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("[Learning] LLM 风格提炼失败: {}", e.getMessage());
        }

        return generateFallbackObservation(style);
    }

    /**
     * 融合新旧值：new = old * (1 - ec) + learned * ec
     */
    private double blend(double oldValue, double learnedValue, double effectiveConfidence) {
        return oldValue * (1 - effectiveConfidence) + learnedValue * effectiveConfidence;
    }

    private String fmt(double val) {
        return String.format("%.2f", val);
    }
}
