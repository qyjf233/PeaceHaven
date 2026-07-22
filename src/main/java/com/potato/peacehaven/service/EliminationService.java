package com.potato.peacehaven.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.entity.ActivityConfig;
import com.potato.peacehaven.entity.ActivityJudge;
import com.potato.peacehaven.entity.PvpRegistration;
import com.potato.peacehaven.entity.SwissMatch;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.ActivityConfigRepository;
import com.potato.peacehaven.repository.ActivityJudgeRepository;
import com.potato.peacehaven.repository.PvpRegistrationRepository;
import com.potato.peacehaven.repository.SwissMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Elimination 淘汰赛赛事服务
 * <p>
 * 负责八强晋级、BO赛制、成绩提交和自动推进。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EliminationService {

    private final SwissMatchRepository matchRepository;
    private final PvpRegistrationRepository registrationRepository;
    private final ActivityJudgeRepository judgeRepository;
    private final ActivityConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每批同时进行的最多比赛数 */
    private static final int MAX_CONCURRENT_MATCHES = 2;

    /** 淘汰赛阶段列表 */
    private static final List<String> ELIMINATION_STAGES = Arrays.asList(
            "QUARTER_FINAL", "SEMI_FINAL", "FINAL", "THIRD_PLACE");

    // ==================== 八强初始化 ====================

    /**
     * 初始化八强赛（Quarter Final）
     * <p>
     * 根据 Swiss 最终排行取 TOP 8，固定种子对阵：
     * QF_A: Rank1 vs Rank8, QF_B: Rank4 vs Rank5,
     * QF_C: Rank2 vs Rank7, QF_D: Rank3 vs Rank6
     * </p>
     */
    @Transactional
    public List<SwissMatch> initQuarterFinals(Long activityId) {
        // 防重复
        long existingCount = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "QUARTER_FINAL", "WAITING");
        if (existingCount > 0) {
            log.warn("八强赛已存在 WAITING 比赛，跳过初始化");
            return matchRepository.findByActivityIdAndStageOrderByMatchOrderAsc(activityId, "QUARTER_FINAL");
        }

        // 获取 TOP 8（按积分降序，积分同按胜场降序，再同按userId稳定排序）
        List<PvpRegistration> allRegs = new ArrayList<>(
                registrationRepository.findByActivityIdOrderByPointsDescWinsDesc(activityId));

        if (allRegs.size() < 8) {
            log.warn("选手不足8人（当前{}人），无法初始化淘汰赛", allRegs.size());
            return Collections.emptyList();
        }

        // 稳定排序：积分DESC → 胜场DESC → userId ASC
        allRegs.sort((a, b) -> {
            int cmp = Integer.compare(b.getPoints(), a.getPoints());
            if (cmp != 0) return cmp;
            cmp = Integer.compare(b.getWins(), a.getWins());
            if (cmp != 0) return cmp;
            return Long.compare(a.getUser().getId(), b.getUser().getId());
        });

        List<PvpRegistration> top8 = allRegs.subList(0, 8);
        log.info("[淘汰赛] TOP8: {}", top8.stream()
                .map(r -> r.getUser().getNickname() + "(" + r.getPoints() + "分)")
                .collect(Collectors.joining(", ")));

        // 固定种子对阵
        // QF_A: Rank1(0) vs Rank8(7)
        // QF_B: Rank4(3) vs Rank5(4)
        // QF_C: Rank2(1) vs Rank7(6)
        // QF_D: Rank3(2) vs Rank6(5)
        int[][] matchups = {
                {0, 7}, // QF_A
                {3, 4}, // QF_B
                {1, 6}, // QF_C
                {2, 5}  // QF_D
        };
        String[] groups = {"QF_A", "QF_B", "QF_C", "QF_D"};

        List<ActivityJudge> judges = judgeRepository.findByActivityIdOrderBySortOrderAsc(activityId);
        List<SwissMatch> matches = new ArrayList<>();
        int judgeIdx = 0;

        for (int i = 0; i < matchups.length; i++) {
            PvpRegistration p1 = top8.get(matchups[i][0]);
            PvpRegistration p2 = top8.get(matchups[i][1]);

            ActivityJudge judge = judges.isEmpty() ? null
                    : judges.get(judgeIdx++ % judges.size());

            SwissMatch match = SwissMatch.builder()
                    .activityId(activityId)
                    .roundNumber(100) // 淘汰赛使用 100+ 编号
                    .matchOrder(i + 1)
                    .player1Id(p1.getUser().getId())
                    .player2Id(p2.getUser().getId())
                    .player1Name(p1.getUser().getNickname())
                    .player2Name(p2.getUser().getNickname())
                    .player1Score(p1.getPoints())
                    .player2Score(p2.getPoints())
                    .refereeId(judge != null ? judge.getUser().getId() : null)
                    .refereeName(judge != null ? judge.getUser().getNickname() : null)
                    .status("WAITING")
                    .stage("QUARTER_FINAL")
                    .bestOf(1)
                    .bracketGroup(groups[i])
                    .build();
            matches.add(match);
        }

        matchRepository.saveAll(matches);
        log.info("[淘汰赛] 八强初始化完成，共 {} 场", matches.size());

        // 自动推进第一批
        startEliminationBatch(activityId, "QUARTER_FINAL");

        return matches;
    }

    // ==================== 成绩提交 ====================

    /**
     * 裁判提交淘汰赛成绩（支持 BO1 和 BO3）
     *
     * @param activityId 活动ID
     * @param matchId    比赛ID
     * @param winnerId   本局胜者ID
     * @param referee    裁判
     * @return 更新后的比赛记录
     */
    @Transactional
    public SwissMatch submitGameResult(Long activityId, Long matchId, Long winnerId, User referee) {
        SwissMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("比赛不存在: " + matchId));

        // 校验
        if (!match.getActivityId().equals(activityId)) {
            throw new RuntimeException("比赛不属于该活动");
        }
        if ("COMPLETED".equals(match.getStatus())) {
            throw new RuntimeException("该比赛已结束，不可重复提交");
        }
        if (!judgeRepository.existsByActivityIdAndUserId(activityId, referee.getId())) {
            throw new RuntimeException("您不是本次活动的裁判，无权提交成绩");
        }
        if (!winnerId.equals(match.getPlayer1Id()) && !winnerId.equals(match.getPlayer2Id())) {
            throw new RuntimeException("胜者必须是参赛选手之一");
        }

        int bestOf = match.getBestOf() != null ? match.getBestOf() : 1;

        if (bestOf == 1) {
            // BO1：直接结束
            completeMatch(match, winnerId);
            log.info("[淘汰赛] BO1结束: {} 胜 {} ({})",
                    match.getWinnerName(),
                    winnerId.equals(match.getPlayer1Id()) ? match.getPlayer2Name() : match.getPlayer1Name(),
                    match.getStage());
        } else {
            // BO3：小局计分
            boolean p1Wins = winnerId.equals(match.getPlayer1Id());
            if (p1Wins) {
                match.setPlayer1GameWin(match.getPlayer1GameWin() + 1);
            } else {
                match.setPlayer2GameWin(match.getPlayer2GameWin() + 1);
            }
            match.setCurrentGameIndex(match.getCurrentGameIndex() + 1);

            String winnerName = p1Wins ? match.getPlayer1Name() : match.getPlayer2Name();
            log.info("[淘汰赛] BO3第{}局: {} 胜 (比分 {}:{})",
                    match.getCurrentGameIndex(), winnerName,
                    match.getPlayer1GameWin(), match.getPlayer2GameWin());

            // 检查是否有人达到2胜
            int winsNeeded = (bestOf / 2) + 1; // BO3需要2胜
            if (match.getPlayer1GameWin() >= winsNeeded || match.getPlayer2GameWin() >= winsNeeded) {
                Long matchWinnerId = match.getPlayer1GameWin() >= winsNeeded
                        ? match.getPlayer1Id() : match.getPlayer2Id();
                completeMatch(match, matchWinnerId);
                log.info("[淘汰赛] BO3结束: {} {}:{} {} ({})",
                        match.getPlayer1Name(), match.getPlayer1GameWin(),
                        match.getPlayer2GameWin(), match.getPlayer2Name(),
                        match.getStage());
            } else {
                matchRepository.save(match);
            }
        }

        // 推进淘汰赛
        advanceElimination(activityId);

        return match;
    }

    /**
     * 完成一场比赛
     */
    private void completeMatch(SwissMatch match, Long winnerId) {
        String winnerName = winnerId.equals(match.getPlayer1Id())
                ? match.getPlayer1Name() : match.getPlayer2Name();
        match.setWinnerId(winnerId);
        match.setWinnerName(winnerName);
        match.setStatus("COMPLETED");
        match.setEndTime(LocalDateTime.now());
        matchRepository.save(match);
    }

    // ==================== 自动晋级 ====================

    /**
     * 淘汰赛自动推进
     */
    private void advanceElimination(Long activityId) {
        // 检查 QF 是否全部完成
        long qfRemaining = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "QUARTER_FINAL", "WAITING")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "QUARTER_FINAL", "ONGOING");

        if (qfRemaining > 0) {
            startEliminationBatch(activityId, "QUARTER_FINAL");
            return;
        }

        // QF 全部完成 → 检查 SF 是否已初始化
        long sfCount = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "SEMI_FINAL", "COMPLETED")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "SEMI_FINAL", "WAITING")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "SEMI_FINAL", "ONGOING");

        if (sfCount == 0) {
            initSemiFinals(activityId);
            return;
        }

        // 检查 SF 是否全部完成
        long sfRemaining = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "SEMI_FINAL", "WAITING")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "SEMI_FINAL", "ONGOING");

        if (sfRemaining > 0) {
            startEliminationBatch(activityId, "SEMI_FINAL");
            return;
        }

        // SF 全部完成 → 检查 Final + Third 是否已初始化
        long finalCount = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "FINAL", "COMPLETED")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "FINAL", "WAITING")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "FINAL", "ONGOING");

        if (finalCount == 0) {
            initFinalsAndThirdPlace(activityId);
            return;
        }

        // 检查 Final + Third 是否全部完成
        long finalRemaining = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "FINAL", "WAITING")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "FINAL", "ONGOING");
        long thirdRemaining = matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "THIRD_PLACE", "WAITING")
                + matchRepository.countByActivityIdAndStageAndStatus(
                activityId, "THIRD_PLACE", "ONGOING");

        if (finalRemaining > 0 || thirdRemaining > 0) {
            startEliminationBatch(activityId, "FINAL");
            startEliminationBatch(activityId, "THIRD_PLACE");
            return;
        }

        // 全部完成！生成幸运参与奖（一次性）
        log.info("[淘汰赛] 所有比赛已完成！");
        generateLuckyWinners(activityId);
    }

    /**
     * 初始化半决赛（Semi Final）
     * SF1: Winner(QF_A) vs Winner(QF_C), SF2: Winner(QF_B) vs Winner(QF_D)
     */
    private void initSemiFinals(Long activityId) {
        List<SwissMatch> qfMatches = matchRepository.findByActivityIdAndStageOrderByMatchOrderAsc(
                activityId, "QUARTER_FINAL");

        // 按 bracketGroup 找到各组胜者
        Map<String, SwissMatch> qfByGroup = new HashMap<>();
        for (SwissMatch m : qfMatches) {
            if (m.getBracketGroup() != null && m.getWinnerId() != null) {
                qfByGroup.put(m.getBracketGroup(), m);
            }
        }

        if (qfByGroup.size() < 4) {
            log.warn("八强赛胜者不足4场，无法初始化半决赛");
            return;
        }

        SwissMatch qfA = qfByGroup.get("QF_A");
        SwissMatch qfB = qfByGroup.get("QF_B");
        SwissMatch qfC = qfByGroup.get("QF_C");
        SwissMatch qfD = qfByGroup.get("QF_D");

        List<ActivityJudge> judges = judgeRepository.findByActivityIdOrderBySortOrderAsc(activityId);
        int judgeIdx = 0;

        // SF1: Winner(QF_A) vs Winner(QF_C)
        SwissMatch sf1 = buildEliminationMatch(activityId, 200, 1,
                qfA.getWinnerId(), qfA.getWinnerName(),
                qfC.getWinnerId(), qfC.getWinnerName(),
                judges.isEmpty() ? null : judges.get(judgeIdx++ % judges.size()),
                "SEMI_FINAL", 3, "SF1");

        // SF2: Winner(QF_B) vs Winner(QF_D)
        SwissMatch sf2 = buildEliminationMatch(activityId, 200, 2,
                qfB.getWinnerId(), qfB.getWinnerName(),
                qfD.getWinnerId(), qfD.getWinnerName(),
                judges.isEmpty() ? null : judges.get(judgeIdx++ % judges.size()),
                "SEMI_FINAL", 3, "SF2");

        matchRepository.saveAll(Arrays.asList(sf1, sf2));
        log.info("[淘汰赛] 半决赛初始化完成: {} vs {}, {} vs {}",
                sf1.getPlayer1Name(), sf1.getPlayer2Name(),
                sf2.getPlayer1Name(), sf2.getPlayer2Name());

        startEliminationBatch(activityId, "SEMI_FINAL");
    }

    /**
     * 初始化决赛和季军赛
     * Final: Winner(SF1) vs Winner(SF2), Third: Loser(SF1) vs Loser(SF2)
     */
    private void initFinalsAndThirdPlace(Long activityId) {
        List<SwissMatch> sfMatches = matchRepository.findByActivityIdAndStageOrderByMatchOrderAsc(
                activityId, "SEMI_FINAL");

        Map<String, SwissMatch> sfByGroup = new HashMap<>();
        for (SwissMatch m : sfMatches) {
            if (m.getBracketGroup() != null && m.getWinnerId() != null) {
                sfByGroup.put(m.getBracketGroup(), m);
            }
        }

        SwissMatch sf1 = sfByGroup.get("SF1");
        SwissMatch sf2 = sfByGroup.get("SF2");

        if (sf1 == null || sf2 == null) {
            log.warn("半决赛数据不完整，无法初始化决赛");
            return;
        }

        // 确定胜者和败者
        Long sf1WinnerId = sf1.getWinnerId();
        String sf1WinnerName = sf1.getWinnerName();
        Long sf1LoserId = sf1WinnerId.equals(sf1.getPlayer1Id()) ? sf1.getPlayer2Id() : sf1.getPlayer1Id();
        String sf1LoserName = sf1WinnerId.equals(sf1.getPlayer1Id()) ? sf1.getPlayer2Name() : sf1.getPlayer1Name();

        Long sf2WinnerId = sf2.getWinnerId();
        String sf2WinnerName = sf2.getWinnerName();
        Long sf2LoserId = sf2WinnerId.equals(sf2.getPlayer1Id()) ? sf2.getPlayer2Id() : sf2.getPlayer1Id();
        String sf2LoserName = sf2WinnerId.equals(sf2.getPlayer1Id()) ? sf2.getPlayer2Name() : sf2.getPlayer1Name();

        List<ActivityJudge> judges = judgeRepository.findByActivityIdOrderBySortOrderAsc(activityId);
        int judgeIdx = 0;

        // Final: BO3
        SwissMatch finalMatch = buildEliminationMatch(activityId, 300, 1,
                sf1WinnerId, sf1WinnerName, sf2WinnerId, sf2WinnerName,
                judges.isEmpty() ? null : judges.get(judgeIdx++ % judges.size()),
                "FINAL", 3, "FINAL");

        // Third Place: BO1
        SwissMatch thirdMatch = buildEliminationMatch(activityId, 300, 2,
                sf1LoserId, sf1LoserName, sf2LoserId, sf2LoserName,
                judges.isEmpty() ? null : judges.get(judgeIdx++ % judges.size()),
                "THIRD_PLACE", 1, "THIRD");

        matchRepository.saveAll(Arrays.asList(finalMatch, thirdMatch));
        log.info("[淘汰赛] 决赛: {} vs {} (BO3), 季军赛: {} vs {} (BO1)",
                finalMatch.getPlayer1Name(), finalMatch.getPlayer2Name(),
                thirdMatch.getPlayer1Name(), thirdMatch.getPlayer2Name());

        startEliminationBatch(activityId, "FINAL");
        startEliminationBatch(activityId, "THIRD_PLACE");
    }

    // ==================== 比赛调度 ====================

    /**
     * 推进淘汰赛某阶段的 WAITING 比赛为 ONGOING
     */
    private void startEliminationBatch(Long activityId, String stage) {
        long ongoingCount = matchRepository.countByActivityIdAndStatus(activityId, "ONGOING");
        if (ongoingCount >= MAX_CONCURRENT_MATCHES) return;

        int slots = MAX_CONCURRENT_MATCHES - (int) ongoingCount;
        List<SwissMatch> waiting = matchRepository.findByActivityIdAndStageAndStatusOrderByMatchOrderAsc(
                activityId, stage, "WAITING");

        LocalDateTime now = LocalDateTime.now();
        for (SwissMatch m : waiting) {
            if (slots <= 0) break;
            m.setStatus("ONGOING");
            m.setStartTime(now);
            matchRepository.save(m);
            slots--;
            log.info("[淘汰赛] 比赛开始: {} vs {} ({})",
                    m.getPlayer1Name(), m.getPlayer2Name(), m.getStage());
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取淘汰赛状态（含所有阶段比赛数据）
     */
    public Map<String, Object> getEliminationStatus(Long activityId, User currentUser) {
        List<SwissMatch> allElimMatches = matchRepository.findByActivityIdAndStageInOrderByMatchOrderAsc(
                activityId, ELIMINATION_STAGES);

        // 按阶段分组
        Map<String, List<Map<String, Object>>> bracket = new LinkedHashMap<>();
        for (String stage : ELIMINATION_STAGES) {
            bracket.put(stage, new ArrayList<>());
        }
        for (SwissMatch m : allElimMatches) {
            Map<String, Object> data = buildMatchData(m);
            List<Map<String, Object>> stageList = bracket.get(m.getStage());
            if (stageList != null) {
                stageList.add(data);
            }
        }

        // 判断当前阶段
        String currentStage = "QUARTER_FINAL";
        for (String stage : ELIMINATION_STAGES) {
            List<Map<String, Object>> matches = bracket.get(stage);
            if (matches != null && !matches.isEmpty()) {
                currentStage = stage;
            }
        }

        // 是否全部完成
        boolean allDone = true;
        for (String stage : ELIMINATION_STAGES) {
            List<Map<String, Object>> matches = bracket.get(stage);
            if (matches != null) {
                for (Map<String, Object> m : matches) {
                    if (!"COMPLETED".equals(m.get("status"))) {
                        allDone = false;
                        break;
                    }
                }
            }
            if (!allDone) break;
        }

        boolean isJudge = currentUser != null
                && judgeRepository.existsByActivityIdAndUserId(activityId, currentUser.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("bracket", bracket);
        result.put("currentStage", currentStage);
        result.put("allDone", allDone && !allElimMatches.isEmpty());
        result.put("isJudge", isJudge);

        // 全部完成时返回幸运参与奖
        if (allDone && !allElimMatches.isEmpty()) {
            result.put("luckyWinners", getLuckyWinners(activityId));
        }

        return result;
    }

    /**
     * 裁判查看自己负责的淘汰赛比赛
     */
    public List<Map<String, Object>> getMyEliminationMatches(Long activityId, Long refereeId) {
        List<SwissMatch> matches = matchRepository.findByActivityIdAndStageInOrderByMatchOrderAsc(
                activityId, ELIMINATION_STAGES);

        return matches.stream()
                .filter(m -> refereeId.equals(m.getRefereeId()))
                .map(this::buildMatchData)
                .collect(Collectors.toList());
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成幸运参与奖：从非冠亚季军的选手中随机抽取5位
     * 一次性生成，持久化到 ActivityConfig.configJson
     */
    private void generateLuckyWinners(Long activityId) {
        // 防重复：检查是否已生成
        List<Map<String, Object>> existing = getLuckyWinners(activityId);
        if (existing != null && !existing.isEmpty()) {
            log.info("[淘汰赛] 幸运参与奖已存在，跳过生成");
            return;
        }

        // 获取冠亚季军ID
        Set<Long> podiumIds = new HashSet<>();
        List<SwissMatch> finalMatches = matchRepository.findByActivityIdAndStageOrderByMatchOrderAsc(activityId, "FINAL");
        List<SwissMatch> thirdMatches = matchRepository.findByActivityIdAndStageOrderByMatchOrderAsc(activityId, "THIRD_PLACE");

        if (!finalMatches.isEmpty()) {
            SwissMatch fm = finalMatches.get(0);
            if (fm.getWinnerId() != null) {
                podiumIds.add(fm.getWinnerId()); // 冠军
                podiumIds.add(fm.getWinnerId().equals(fm.getPlayer1Id()) ? fm.getPlayer2Id() : fm.getPlayer1Id()); // 亚军
            }
        }
        if (!thirdMatches.isEmpty()) {
            SwissMatch tm = thirdMatches.get(0);
            if (tm.getWinnerId() != null) {
                podiumIds.add(tm.getWinnerId()); // 季军
            }
        }

        // 获取所有报名选手，排除冠亚季军
        List<PvpRegistration> allRegs = registrationRepository.findByActivityIdOrderByPointsDescWinsDesc(activityId);
        List<PvpRegistration> candidates = allRegs.stream()
                .filter(r -> !podiumIds.contains(r.getUser().getId()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.warn("[淘汰赛] 无候选选手，无法生成幸运参与奖");
            return;
        }

        // 随机抽取5位（或全部，如果不足5人）
        int count = Math.min(5, candidates.size());
        Collections.shuffle(candidates);
        List<PvpRegistration> winners = candidates.subList(0, count);

        // 构建 JSON 数据
        List<Map<String, Object>> luckyList = new ArrayList<>();
        for (PvpRegistration w : winners) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", w.getUser().getId());
            item.put("nickname", w.getUser().getNickname());
            luckyList.add(item);
        }

        // 持久化到 ActivityConfig
        try {
            ActivityConfig config = configRepository.findByActivityId(activityId).orElse(null);
            if (config == null) {
                config = ActivityConfig.builder().activityId(activityId).configJson("{}").build();
            }

            Map<String, Object> configMap;
            try {
                configMap = objectMapper.readValue(config.getConfigJson(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                configMap = new LinkedHashMap<>();
            }

            configMap.put("luckyWinners", luckyList);
            config.setConfigJson(objectMapper.writeValueAsString(configMap));
            configRepository.save(config);

            log.info("[淘汰赛] 幸运参与奖已生成: {}",
                    winners.stream().map(w -> w.getUser().getNickname()).collect(Collectors.joining(", ")));
        } catch (Exception e) {
            log.error("[淘汰赛] 幸运参与奖生成失败", e);
        }
    }

    /**
     * 读取已生成的幸运参与奖
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getLuckyWinners(Long activityId) {
        try {
            ActivityConfig config = configRepository.findByActivityId(activityId).orElse(null);
            if (config == null) return Collections.emptyList();

            Map<String, Object> configMap = objectMapper.readValue(config.getConfigJson(),
                    new TypeReference<Map<String, Object>>() {});
            Object lucky = configMap.get("luckyWinners");
            if (lucky instanceof List) {
                return (List<Map<String, Object>>) lucky;
            }
        } catch (Exception e) {
            log.warn("[淘汰赛] 读取幸运参与奖失败", e);
        }
        return Collections.emptyList();
    }

    /**
     * 构建淘汰赛比赛记录
     */
    private SwissMatch buildEliminationMatch(Long activityId, int roundNumber, int matchOrder,
                                              Long p1Id, String p1Name,
                                              Long p2Id, String p2Name,
                                              ActivityJudge judge,
                                              String stage, int bestOf, String bracketGroup) {
        return SwissMatch.builder()
                .activityId(activityId)
                .roundNumber(roundNumber)
                .matchOrder(matchOrder)
                .player1Id(p1Id)
                .player2Id(p2Id)
                .player1Name(p1Name)
                .player2Name(p2Name)
                .player1Score(0)
                .player2Score(0)
                .refereeId(judge != null ? judge.getUser().getId() : null)
                .refereeName(judge != null ? judge.getUser().getNickname() : null)
                .status("WAITING")
                .stage(stage)
                .bestOf(bestOf)
                .bracketGroup(bracketGroup)
                .build();
    }

    private Map<String, Object> buildMatchData(SwissMatch m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", m.getId());
        data.put("roundNumber", m.getRoundNumber());
        data.put("matchOrder", m.getMatchOrder());
        data.put("player1Id", m.getPlayer1Id());
        data.put("player2Id", m.getPlayer2Id());
        data.put("player1Name", m.getPlayer1Name());
        data.put("player2Name", m.getPlayer2Name());
        data.put("player1Score", m.getPlayer1Score());
        data.put("player2Score", m.getPlayer2Score());
        data.put("winnerId", m.getWinnerId());
        data.put("winnerName", m.getWinnerName());
        data.put("refereeId", m.getRefereeId());
        data.put("refereeName", m.getRefereeName());
        data.put("status", m.getStatus());
        data.put("stage", m.getStage());
        data.put("bestOf", m.getBestOf());
        data.put("player1GameWin", m.getPlayer1GameWin());
        data.put("player2GameWin", m.getPlayer2GameWin());
        data.put("currentGameIndex", m.getCurrentGameIndex());
        data.put("bracketGroup", m.getBracketGroup());
        data.put("startTime", m.getStartTime() != null ? m.getStartTime().toString() : null);
        data.put("endTime", m.getEndTime() != null ? m.getEndTime().toString() : null);
        return data;
    }

    /**
     * 检查淘汰赛是否已初始化
     */
    public boolean isEliminationInitialized(Long activityId) {
        for (String stage : ELIMINATION_STAGES) {
            long count = matchRepository.countByActivityIdAndStageAndStatus(activityId, stage, "WAITING")
                    + matchRepository.countByActivityIdAndStageAndStatus(activityId, stage, "ONGOING")
                    + matchRepository.countByActivityIdAndStageAndStatus(activityId, stage, "COMPLETED");
            if (count > 0) return true;
        }
        return false;
    }
}
