package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.ActivityJudge;
import com.potato.peacehaven.entity.PvpRegistration;
import com.potato.peacehaven.entity.SwissMatch;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.ActivityJudgeRepository;
import com.potato.peacehaven.repository.PvpRegistrationRepository;
import com.potato.peacehaven.repository.SwissMatchRepository;
import com.potato.peacehaven.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Swiss 瑞士轮赛事服务
 * <p>
 * 负责分组生成、比赛调度、成绩提交和轮次自动推进。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwissRoundService {

    private final SwissMatchRepository matchRepository;
    private final PvpRegistrationRepository registrationRepository;
    private final ActivityJudgeRepository judgeRepository;
    private final UserRepository userRepository;
    private final EliminationService eliminationService;

    /** 瑞士轮总轮次数 */
    private static final int TOTAL_ROUNDS = 4;

    /** 每批同时进行的最多比赛数（等于裁判数） */
    private static final int MAX_CONCURRENT_MATCHES = 2;

    // ==================== 分组初始化 ====================

    /**
     * 初始化某一轮 Swiss 比赛
     * <p>
     * 根据当前积分降序排列所有报名选手，相邻积分选手两两配对，
     * 奇数选手时最后一个轮空（自动胜，+3分）。
     * </p>
     *
     * @param activityId  活动ID
     * @param roundNumber 轮次号（从1开始）
     */
    @Transactional
    public List<SwissMatch> initRound(Long activityId, int roundNumber) {
        // 防止重复初始化
        long existingCount = matchRepository.countByActivityIdAndRoundNumberAndStatus(
                activityId, roundNumber, "WAITING");
        if (existingCount > 0) {
            log.warn("轮次 {} 已存在 WAITING 比赛，跳过初始化", roundNumber);
            return matchRepository.findByActivityIdAndRoundNumberOrderByMatchOrderAsc(activityId, roundNumber);
        }

        // 获取所有报名选手并按积分降序排列
        List<PvpRegistration> registrations = new ArrayList<>(
                registrationRepository.findByActivityIdOrderByPointsDescWinsDesc(activityId));

        if (registrations.size() < 2) {
            log.warn("参赛选手不足2人，无法初始化轮次 {}", roundNumber);
            return Collections.emptyList();
        }

        Random random = new Random();

        // 奇数选手：随机抽一位轮空自动获胜
        PvpRegistration byePlayer = null;
        int byePlayerOriginalPoints = 0;
        if (registrations.size() % 2 == 1) {
            int byeIdx = random.nextInt(registrations.size());
            byePlayer = registrations.remove(byeIdx);
            byePlayerOriginalPoints = byePlayer.getPoints();
            byePlayer.setWins(byePlayer.getWins() + 1);
            byePlayer.setPoints(byePlayer.getPoints() + 3);
            registrationRepository.save(byePlayer);
            log.info("轮次 {} 选手 {} 被随机抽中轮空，自动获胜，积分 {} -> {}",
                    roundNumber, byePlayer.getUser().getNickname(),
                    byePlayerOriginalPoints, byePlayer.getPoints());
        }

        // 按积分分组（LinkedHashMap 保持积分从高到低的插入顺序）
        LinkedHashMap<Integer, List<PvpRegistration>> groups = new LinkedHashMap<>();
        for (PvpRegistration reg : registrations) {
            groups.computeIfAbsent(reg.getPoints(), k -> new ArrayList<>()).add(reg);
        }

        // 日志：打印分组情况
        log.info("[Swiss轮次{}] 选手积分分布: {}", roundNumber,
                groups.entrySet().stream()
                        .map(e -> e.getKey() + "分=" + e.getValue().stream()
                                .map(r -> r.getUser().getNickname())
                                .collect(Collectors.joining(",")))
                        .collect(Collectors.joining(" | ")));

        // 从高分到低分依次配对，奇数溢出到下一组
        // 溢出选手（来自更高积分组）优先配对，排在 pool 前面
        List<PvpRegistration> overflow = new ArrayList<>();
        List<PvpRegistration[]> pairs = new ArrayList<>();

        for (Map.Entry<Integer, List<PvpRegistration>> entry : groups.entrySet()) {
            // overflow 在前（高积分溢出优先配对），当前组在后
            List<PvpRegistration> overflowShuffled = new ArrayList<>(overflow);
            Collections.shuffle(overflowShuffled, random);

            List<PvpRegistration> groupShuffled = new ArrayList<>(entry.getValue());
            Collections.shuffle(groupShuffled, random);

            List<PvpRegistration> pool = new ArrayList<>(overflowShuffled);
            pool.addAll(groupShuffled);
            overflow.clear();

            for (int i = 0; i < pool.size() - 1; i += 2) {
                pairs.add(new PvpRegistration[]{pool.get(i), pool.get(i + 1)});
            }

            // 奇数剩余，溢出到下一积分组
            if (pool.size() % 2 == 1) {
                overflow.add(pool.get(pool.size() - 1));
            }
        }

        // 日志：打印配对结果
        log.info("[Swiss轮次{}] 配对结果: {}", roundNumber,
                pairs.stream()
                        .map(p -> p[0].getUser().getNickname() + "(" + p[0].getPoints()
                                + ") vs " + p[1].getUser().getNickname() + "(" + p[1].getPoints() + ")")
                        .collect(Collectors.joining(", ")));

        // 获取裁判列表（轮转分配）
        List<ActivityJudge> judges = judgeRepository.findByActivityIdOrderBySortOrderAsc(activityId);

        // 生成比赛记录
        List<SwissMatch> matches = new ArrayList<>();
        int matchOrder = 1;
        int[] judgeIdx = {0};

        for (PvpRegistration[] pair : pairs) {
            PvpRegistration p1 = pair[0];
            PvpRegistration p2 = pair[1];

            ActivityJudge assignedJudge = judges.isEmpty() ? null
                    : judges.get(judgeIdx[0]++ % judges.size());

            SwissMatch match = SwissMatch.builder()
                    .activityId(activityId)
                    .roundNumber(roundNumber)
                    .matchOrder(matchOrder++)
                    .player1Id(p1.getUser().getId())
                    .player2Id(p2.getUser().getId())
                    .player1Name(p1.getUser().getNickname())
                    .player2Name(p2.getUser().getNickname())
                    .player1Score(p1.getPoints())
                    .player2Score(p2.getPoints())
                    .refereeId(assignedJudge != null ? assignedJudge.getUser().getId() : null)
                    .refereeName(assignedJudge != null ? assignedJudge.getUser().getNickname() : null)
                    .status("WAITING")
                    .build();
            matches.add(match);
        }

        matchRepository.saveAll(matches);
        log.info("轮次 {} 初始化完成，共 {} 场比赛", roundNumber, matches.size());

        // 为轮空选手创建比赛记录（matchOrder=0，排在最前面）
        if (byePlayer != null) {
            SwissMatch byeMatch = SwissMatch.builder()
                    .activityId(activityId)
                    .roundNumber(roundNumber)
                    .matchOrder(0)
                    .player1Id(byePlayer.getUser().getId())
                    .player2Id(byePlayer.getUser().getId())
                    .player1Name(byePlayer.getUser().getNickname())
                    .player2Name("轮空")
                    .player1Score(byePlayerOriginalPoints)
                    .player2Score(0)
                    .winnerId(byePlayer.getUser().getId())
                    .winnerName(byePlayer.getUser().getNickname())
                    .status("BYE")
                    .build();
            matchRepository.save(byeMatch);
            log.info("轮次 {} 轮空比赛记录已创建: {} (BYE)", roundNumber, byePlayer.getUser().getNickname());
        }

        // 初始化后自动推进第一批比赛
        startNextBatch(activityId, roundNumber);

        return matches;
    }

    // ==================== 比赛调度 ====================

    /**
     * 推进下一批比赛：将 WAITING 状态的比赛改为 ONGOING
     * <p>
     * 每批最多 MAX_CONCURRENT_MATCHES 场同时进行。
     * </p>
     */
    @Transactional
    public int startNextBatch(Long activityId, int roundNumber) {
        // 统计当前正在进行的比赛数
        long ongoingCount = matchRepository.countByActivityIdAndStatus(activityId, "ONGOING");

        if (ongoingCount >= MAX_CONCURRENT_MATCHES) {
            log.debug("当前已有 {} 场进行中的比赛，暂不推进", ongoingCount);
            return 0;
        }

        int slots = MAX_CONCURRENT_MATCHES - (int) ongoingCount;

        // 取当前轮的 WAITING 比赛
        List<SwissMatch> waitingMatches = matchRepository
                .findByActivityIdAndRoundNumberAndStatusOrderByMatchOrderAsc(
                        activityId, roundNumber, "WAITING");

        int started = 0;
        LocalDateTime now = LocalDateTime.now();
        for (SwissMatch match : waitingMatches) {
            if (started >= slots) break;
            match.setStatus("ONGOING");
            match.setStartTime(now);
            matchRepository.save(match);
            started++;
            log.info("比赛 #{} 开始: {} vs {} (裁判: {})",
                    match.getMatchOrder(), match.getPlayer1Name(),
                    match.getPlayer2Name(), match.getRefereeName());
        }

        return started;
    }

    // ==================== 成绩提交 ====================

    /**
     * 裁判提交比赛结果
     *
     * @param activityId 活动ID
     * @param matchId    比赛ID
     * @param winnerId   胜者用户ID（必须是参赛选手之一）
     * @param referee    提交结果的裁判用户
     * @return 更新后的比赛记录
     */
    @Transactional
    public SwissMatch submitResult(Long activityId, Long matchId, Long winnerId, User referee) {
        SwissMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("比赛不存在: " + matchId));

        // 校验比赛属于当前活动
        if (!match.getActivityId().equals(activityId)) {
            throw new RuntimeException("比赛不属于该活动");
        }

        // 校验比赛状态
        if ("COMPLETED".equals(match.getStatus())) {
            throw new RuntimeException("该比赛已结束，不可重复提交");
        }

        // 校验裁判身份
        if (!judgeRepository.existsByActivityIdAndUserId(activityId, referee.getId())) {
            throw new RuntimeException("您不是本次活动的裁判，无权提交成绩");
        }

        // 校验胜者是参赛选手之一
        if (!winnerId.equals(match.getPlayer1Id()) && !winnerId.equals(match.getPlayer2Id())) {
            throw new RuntimeException("胜者必须是参赛选手之一");
        }

        // 确定败者
        Long loserId = winnerId.equals(match.getPlayer1Id()) ? match.getPlayer2Id() : match.getPlayer1Id();
        String winnerName = winnerId.equals(match.getPlayer1Id())
                ? match.getPlayer1Name() : match.getPlayer2Name();

        // 更新比赛记录
        match.setWinnerId(winnerId);
        match.setWinnerName(winnerName);
        match.setStatus("COMPLETED");
        match.setEndTime(LocalDateTime.now());
        matchRepository.save(match);

        // 更新选手积分
        updatePlayerScore(winnerId, true);
        updatePlayerScore(loserId, false);

        log.info("比赛结果提交: {} 胜 {} (轮次 {})",
                winnerName,
                winnerId.equals(match.getPlayer1Id()) ? match.getPlayer2Name() : match.getPlayer1Name(),
                match.getRoundNumber());

        // 检查当前轮是否全部完成，推进下一批或下一轮
        advanceAfterCompletion(activityId, match.getRoundNumber());

        return match;
    }

    /**
     * 更新选手积分
     */
    private void updatePlayerScore(Long userId, boolean isWinner) {
        // 查找所有活动中该用户的报名记录（通常只有一个）
        // 这里通过遍历所有报名来处理，实际上一个用户只参加一个活动
        List<PvpRegistration> allRegs = registrationRepository.findAll();
        for (PvpRegistration reg : allRegs) {
            if (reg.getUser().getId().equals(userId)) {
                if (isWinner) {
                    reg.setWins(reg.getWins() + 1);
                    reg.setPoints(reg.getPoints() + 3);
                } else {
                    reg.setLosses(reg.getLosses() + 1);
                }
                registrationRepository.save(reg);
                break;
            }
        }
    }

    /**
     * 完成一场比赛后推进赛程
     */
    private void advanceAfterCompletion(Long activityId, int roundNumber) {
        // 检查当前轮是否还有未完成比赛
        long waitingCount = matchRepository.countByActivityIdAndRoundNumberAndStatus(
                activityId, roundNumber, "WAITING");
        long ongoingCount = matchRepository.countByActivityIdAndRoundNumberAndStatus(
                activityId, roundNumber, "ONGOING");

        if (waitingCount > 0 || ongoingCount > 0) {
            // 当前轮未结束，尝试推进下一批
            startNextBatch(activityId, roundNumber);
            return;
        }

        // 当前轮全部完成
        log.info("轮次 {} 全部完成", roundNumber);

        if (roundNumber >= TOTAL_ROUNDS) {
            log.info("所有 {} 轮 Swiss 比赛已完成，进入淘汰赛阶段", TOTAL_ROUNDS);
            // 自动初始化淘汰赛八强
            try {
                eliminationService.initQuarterFinals(activityId);
            } catch (Exception e) {
                log.warn("淘汰赛初始化异常: {}", e.getMessage());
            }
            return;
        }

        // 自动初始化下一轮
        int nextRound = roundNumber + 1;
        log.info("自动初始化轮次 {}", nextRound);
        initRound(activityId, nextRound);
    }

    // ==================== 查询方法 ====================

    /**
     * 获取当前轮次号和比赛列表
     */
    public Map<String, Object> getSwissStatus(Long activityId, User currentUser) {
        Integer maxRound = matchRepository.findMaxSwissRoundByActivityId(activityId);
        int currentRound = (maxRound != null) ? maxRound : 0;

        List<SwissMatch> currentMatches = currentRound > 0
                ? matchRepository.findByActivityIdAndRoundNumberAndStageOrderByMatchOrderAsc(
                        activityId, currentRound, "SWISS")
                : Collections.emptyList();

        Map<String, Object> result = new HashMap<>();
        result.put("currentRound", currentRound);
        result.put("totalRounds", TOTAL_ROUNDS);
        result.put("matches", buildMatchListData(currentMatches));

        // 判断当前用户是否为裁判
        boolean isJudge = currentUser != null
                && judgeRepository.existsByActivityIdAndUserId(activityId, currentUser.getId());
        result.put("isJudge", isJudge);

        // 如果有比赛尚未开始，标记是否还有等待中的
        long waitingCount = currentRound > 0
                ? matchRepository.countByActivityIdAndRoundNumberAndStageAndStatus(
                        activityId, currentRound, "SWISS", "WAITING")
                : 0;
        long ongoingCount = currentRound > 0
                ? matchRepository.countByActivityIdAndRoundNumberAndStageAndStatus(
                        activityId, currentRound, "SWISS", "ONGOING")
                : 0;
        result.put("hasWaiting", waitingCount > 0);
        result.put("hasOngoing", ongoingCount > 0);
        result.put("allCompleted", currentRound > 0 && waitingCount == 0 && ongoingCount == 0);

        return result;
    }

    /**
     * 获取完整赛程（所有轮次，仅 Swiss 阶段）
     */
    public List<Map<String, Object>> getAllMatches(Long activityId) {
        List<SwissMatch> allMatches = matchRepository
                .findByActivityIdAndStageOrderByMatchOrderAsc(activityId, "SWISS");
        return buildMatchListData(allMatches);
    }

    /**
     * 裁判查看自己负责的比赛（仅 Swiss 阶段）
     */
    public List<Map<String, Object>> getMyMatches(Long activityId, Long refereeId) {
        List<SwissMatch> matches = matchRepository
                .findByActivityIdAndRefereeIdAndStageOrderByRoundNumberAscMatchOrderAsc(
                        activityId, refereeId, "SWISS");
        return buildMatchListData(matches);
    }

    /**
     * 构建比赛列表数据（供前端渲染）
     */
    private List<Map<String, Object>> buildMatchListData(List<SwissMatch> matches) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SwissMatch m : matches) {
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
            data.put("startTime", m.getStartTime() != null ? m.getStartTime().toString() : null);
            data.put("endTime", m.getEndTime() != null ? m.getEndTime().toString() : null);
            list.add(data);
        }
        return list;
    }

    /**
     * 获取当前最大轮次号（仅 Swiss 阶段）
     */
    public int getCurrentRound(Long activityId) {
        Integer maxRound = matchRepository.findMaxSwissRoundByActivityId(activityId);
        return maxRound != null ? maxRound : 0;
    }

    /**
     * 检查某用户是否为活动裁判
     */
    public boolean isJudge(Long activityId, Long userId) {
        return judgeRepository.existsByActivityIdAndUserId(activityId, userId);
    }
}
