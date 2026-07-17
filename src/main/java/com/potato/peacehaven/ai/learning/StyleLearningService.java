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
        log.debug("[Learning] 全局聚合: samples={}, humor={}, sarcasm={}, warmth={}, formal={}",
                globalStyle.getSampleCount(),
                fmt(globalStyle.getHumorAvg()),
                fmt(globalStyle.getSarcasmAvg()),
                fmt(globalStyle.getWarmthAvg()),
                fmt(globalStyle.getFormalAvg()));

        // Step 5: 多维 confidence
        double sampleFactor = Math.min((double) allFeatures.size() / 200, 1.0);
        double timeSpanFactor = computeTimeSpanFactor(selfMessages);
        double timeFactor = 0.5 + 0.5 * timeSpanFactor;
        int distinctSceneTypes = (int) roomSceneType.values().stream().distinct().count();
        double sceneFactor = Math.min((double) distinctSceneTypes / 4, 1.0);
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

        // Step 9: DriftDetector
        if (!bootstrapMode && hashChanged) {
            driftDetector.detectAndUpdateStability(
                    globalStyle.getHumorAvg(),
                    globalStyle.getSarcasmAvg(),
                    globalStyle.getWarmthAvg());
        }

        // Step 10: Stability 正则化融合
        double ec = driftDetector.computeEffectiveConfidence(confidence, "humor");
        double newHumor = blend(config.getHumorScore(), globalStyle.getHumorAvg(), ec);
        double ecSarcasm = driftDetector.computeEffectiveConfidence(confidence, "sarcasm");
        double newSarcasm = blend(config.getSarcasmScore(), globalStyle.getSarcasmAvg(), ecSarcasm);
        double ecWarmth = driftDetector.computeEffectiveConfidence(confidence, "warmth");
        double newWarmth = blend(config.getWarmthScore(), globalStyle.getWarmthAvg(), ecWarmth);

        if (!bootstrapMode) {
            config.setHumorScore(newHumor);
            config.setSarcasmScore(newSarcasm);
            config.setWarmthScore(newWarmth);
            config.setCasualScore(blend(config.getCasualScore(), globalStyle.getDirectnessAvg(), ec));
            config.setFormalScore(globalStyle.getFormalAvg());
            config.setSlangScore(globalStyle.getSlangAvg());
            config.setAvgLength(globalStyle.getLengthAvg());
            config.setLengthVariance(globalStyle.getLengthVariance());
            config.setExpressionVariance(globalStyle.getExpressionVariance());
            config.setIntimacyHumor(globalStyle.getIntimacyHumorAvg());
            config.setEmpathyHidden(globalStyle.getEmpathyHiddenAvg());
            config.setTeasingAllowed(globalStyle.getTeasingAllowedAvg());
        }

        // Step 11: LLM 风格提炼（hash 变化时）
        if (hashChanged && !bootstrapMode) {
            String styleDescription = extractStyleDescription(allFeatures, globalStyle);
            config.setStyleDescription(styleDescription);
        }

        // Step 12: 双版本判断 + Snapshot
        boolean personaChanged = hashChanged && !bootstrapMode &&
                (Math.abs(newHumor - config.getHumorScore()) > 0.1 ||
                 Math.abs(newSarcasm - config.getSarcasmScore()) > 0.1 ||
                 Math.abs(newWarmth - config.getWarmthScore()) > 0.1);

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
        config.setSceneCount(distinctSceneTypes);
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
                    .sceneCount(distinctSceneTypes)
                    .trigger(bootstrapMode ? "bootstrap_collect" : "new_" + allFeatures.size() + "_messages")
                    .build();
            snapshotRepo.save(snapshot);
        }

        log.info("[Learning] 学习完成: hash={}, bootstrap={}, confidence={}, samples={}, scenes={}, personaV={}, styleV={}",
                currentHash.substring(0, 8), bootstrapMode, fmt(confidence),
                allFeatures.size(), distinctSceneTypes,
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
     * 计算时间跨度因子
     */
    private double computeTimeSpanFactor(List<BotChatRecord> messages) {
        if (messages.size() < 2) return 0.0;
        LocalDateTime oldest = messages.get(messages.size() - 1).getCreatedAt();
        LocalDateTime newest = messages.get(0).getCreatedAt();
        if (oldest == null || newest == null) return 0.5;
        long days = Duration.between(oldest, newest).toDays();
        return Math.min((double) days / 90, 1.0);
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

            // 增量融合（70% 旧值 + 30% 新值）
            double alpha = profile.getSampleCount() > 0 ? 0.7 : 0.0;
            profile.setHumorScore(alpha * profile.getHumorScore() + (1 - alpha) * sceneStyle.getHumorAvg());
            profile.setSarcasmScore(alpha * profile.getSarcasmScore() + (1 - alpha) * sceneStyle.getSarcasmAvg());
            profile.setWarmthScore(alpha * profile.getWarmthScore() + (1 - alpha) * sceneStyle.getWarmthAvg());
            profile.setCasualScore(alpha * profile.getCasualScore() + (1 - alpha) * sceneStyle.getDirectnessAvg());
            profile.setFormalScore(sceneStyle.getFormalAvg());
            profile.setSampleCount(profile.getSampleCount() + sceneStyle.getSampleCount());

            sceneRepo.save(profile);
            log.debug("[Learning] SceneProfile {}: humor={}, sarcasm={}, warmth={}, samples={}",
                    sceneType, fmt(profile.getHumorScore()), fmt(profile.getSarcasmScore()),
                    fmt(profile.getWarmthScore()), profile.getSampleCount());
        }
    }

    /**
     * 更新 RelationshipProfile（per-person 画像）
     * <p>简化版：根据对话上下文中对方消息推断，此处基于 roomId 统计</p>
     */
    private void updateRelationshipProfiles(List<BotChatRecord> selfMessages,
                                             Map<String, List<StyleFeature>> featuresByRoom,
                                             Map<String, List<BotChatRecord>> byRoom) {
        // 简化：对每个 roomId，用本人消息特征 + 群名推断关系
        for (var entry : byRoom.entrySet()) {
            String roomId = entry.getKey();
            if ("private".equals(roomId)) continue; // 私聊暂不处理

            List<StyleFeature> roomFeatures = featuresByRoom.get(roomId);
            if (roomFeatures == null || roomFeatures.isEmpty()) continue;

            AggregatedStyle roomStyle = styleAggregator.aggregate(roomFeatures);
            if (roomStyle == null) continue;

            // 用 roomId 作为 contactName 的简化 key（后续可改为真实联系人）
            String contactName = roomId;
            List<BotChatRecord> roomRecords = entry.getValue();
            if (!roomRecords.isEmpty() && roomRecords.get(0).getRoomName() != null) {
                contactName = roomRecords.get(0).getRoomName();
            }

            RelationshipProfile profile = relationshipRepo.findByContactName(contactName)
                    .orElse(RelationshipProfile.builder()
                            .contactName(contactName)
                            .relationshipType("group")
                            .intimacyLevel(5)
                            .build());

            double alpha = profile.getSampleCount() > 0 ? 0.7 : 0.0;
            profile.setHumorScore(alpha * profile.getHumorScore() + (1 - alpha) * roomStyle.getHumorAvg());
            profile.setSarcasmScore(alpha * profile.getSarcasmScore() + (1 - alpha) * roomStyle.getSarcasmAvg());
            profile.setWarmthScore(alpha * profile.getWarmthScore() + (1 - alpha) * roomStyle.getWarmthAvg());
            profile.setFormalScore(roomStyle.getFormalAvg());
            profile.setSampleCount(profile.getSampleCount() + roomStyle.getSampleCount());

            // 推断 communicationStyle
            if (roomStyle.getHumorAvg() > 0.6 && roomStyle.getSarcasmAvg() > 0.5) {
                profile.setCommunicationStyle("互相调侃");
            } else if (roomStyle.getWarmthAvg() > 0.6) {
                profile.setCommunicationStyle("温暖关心");
            } else if (roomStyle.getFormalAvg() > 0.5) {
                profile.setCommunicationStyle("正式交流");
            } else {
                profile.setCommunicationStyle("自然随意");
            }

            relationshipRepo.save(profile);
        }
    }

    /**
     * LLM 风格提炼（从聚合特征生成观察式描述）
     */
    private String extractStyleDescription(List<StyleFeature> features, AggregatedStyle style) {
        try {
            String prompt = buildStyleExtractionPrompt(features, style);
            List<LlmMessage> messages = List.of(
                    LlmMessage.system("你是一个语言风格分析专家。根据聊天统计数据，用观察性描述总结说话风格。不要使用'幽默''吐槽''调侃'等词，用'轻松''随意''简短'等自然词汇。输出 3-5 行简短描述。"),
                    LlmMessage.user(prompt)
            );
            String result = llmClient.chat(messages, 0.3, 300);
            if (result != null && !result.isBlank()) {
                log.debug("[Learning] LLM 风格提炼成功: {}",
                        result.length() > 80 ? result.substring(0, 80) + "..." : result);
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("[Learning] LLM 风格提炼失败: {}", e.getMessage());
        }

        // Fallback：规则生成
        return generateRuleBasedDescription(style);
    }

    private String buildStyleExtractionPrompt(List<StyleFeature> features, AggregatedStyle style) {
        return String.format("""
                聊天统计数据：
                - 消息数：%d
                - 平均长度：%d 字，长度波动：%s
                - 轻松表达占比：%s，正式表达占比：%s
                - 网络用语密度：%s
                - 温暖表达占比：%s，直接表达占比：%s
                
                请用观察性语言描述这个人的说话风格。
                """,
                style.getSampleCount(),
                style.getLengthAvg(),
                style.getLengthVariance() > 500 ? "较大" : "较小",
                fmt(style.getHumorAvg()),
                fmt(style.getFormalAvg()),
                fmt(style.getSlangAvg()),
                fmt(style.getWarmthAvg()),
                fmt(style.getDirectnessAvg()));
    }

    /**
     * 规则生成风格描述（LLM fallback）
     */
    private String generateRuleBasedDescription(AggregatedStyle style) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 交流风格偏");
        if (style.getHumorAvg() > 0.5) sb.append("轻松活泼");
        else if (style.getFormalAvg() > 0.5) sb.append("正式稳重");
        else sb.append("自然随和");
        sb.append("\n");

        sb.append("- 消息通常");
        if (style.getLengthAvg() < 30) sb.append("很简短");
        else if (style.getLengthAvg() < 80) sb.append("中等长度");
        else sb.append("比较长");
        sb.append("\n");

        if (style.getSlangAvg() > 0.3) {
            sb.append("- 经常使用网络用语和非正式表达\n");
        }

        if (style.getLengthVariance() > 500) {
            sb.append("- 表达长度波动较大，多数很短，偶尔长篇\n");
        }

        return sb.toString().trim();
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
