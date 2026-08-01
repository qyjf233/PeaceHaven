package com.potato.peacehaven.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 实时点兵会话管理器（内存态）
 * 每场比赛独立维护一个 DraftSession，通过 SSE 向双方将领推送状态变更
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftSessionManager {

    private final PvpRegistrationRepository registrationRepository;
    private final ActivityConfigRepository configRepository;
    private final UserRepository userRepository;
    private final DraftPickRepository draftPickRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 阶段名 → matchIndex 映射 */
    private static final Map<String, Integer> PHASE_TO_MATCH = Map.of(
            "firstRound", 0, "secondRound", 1,
            "thirdRound", 2, "fourthRound", 3
    );

    /** 每队需要点兵的人数（不含将领自身） */
    public static final int PICKS_PER_TEAM = 11;

    /** 所有活跃的点兵会话 key=matchIndex */
    private final ConcurrentHashMap<Integer, DraftSession> sessions = new ConcurrentHashMap<>();

    // ==================== 内部数据结构 ====================

    /** 单个玩家信息 */
    public record PlayerInfo(Long userId, String nickname, String job) {}

    /** 点兵会话 */
    public static class DraftSession {
        final int matchIndex;
        final Long activityId;
        final String teamAName, teamBName;
        final Long captainAUserId, captainBUserId;
        final String captainAName, captainBName;

        /** 已连接的将领 userId → SseEmitter */
        final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

        /** 当前该谁选 A/B */
        volatile String currentTurn;
        /** 各队已选玩家 */
        final List<PlayerInfo> pickedA = Collections.synchronizedList(new ArrayList<>());
        final List<PlayerInfo> pickedB = Collections.synchronizedList(new ArrayList<>());
        /** 可选玩家池（排除将领） */
        final List<PlayerInfo> availablePlayers = Collections.synchronizedList(new ArrayList<>());
        /** 选人顺序日志 */
        final List<Map<String, Object>> draftLog = Collections.synchronizedList(new ArrayList<>());

        /** WAITING / BOTH_READY / IN_PROGRESS / COMPLETED */
        volatile String status = "WAITING";

        DraftSession(int matchIndex, Long activityId,
                     String teamAName, String teamBName,
                     Long captainAUserId, Long captainBUserId,
                     String captainAName, String captainBName) {
            this.matchIndex = matchIndex;
            this.activityId = activityId;
            this.teamAName = teamAName;
            this.teamBName = teamBName;
            this.captainAUserId = captainAUserId;
            this.captainBUserId = captainBUserId;
            this.captainAName = captainAName;
            this.captainBName = captainBName;
        }

        boolean isCaptain(Long userId) {
            return captainAUserId.equals(userId) || captainBUserId.equals(userId);
        }

        String getSide(Long userId) {
            if (captainAUserId.equals(userId)) return "A";
            if (captainBUserId.equals(userId)) return "B";
            return null;
        }

        boolean isFull(String side) {
            return "A".equals(side) ? pickedA.size() >= PICKS_PER_TEAM
                                    : pickedB.size() >= PICKS_PER_TEAM;
        }

        boolean isComplete() {
            return (pickedA.size() >= PICKS_PER_TEAM && pickedB.size() >= PICKS_PER_TEAM)
                    || availablePlayers.isEmpty();
        }
    }

    // ==================== 公开 API ====================

    /**
     * 将领加入点兵会话（建立 SSE 连接）
     * @return SseEmitter 如果成功; null 如果校验失败
     */
    public SseEmitter joinSession(int matchIndex, Long activityId, Long userId) {
        // 获取配置
        Map<String, Object> config = loadConfig(activityId);

        // 阶段校验：只允许在当前比赛阶段点兵
        int activeMatch = getActiveMatchIndex(config);
        if (activeMatch < 0) throw new RuntimeException("当前不在点兵阶段");
        if (matchIndex != activeMatch) throw new RuntimeException("只能在当前比赛场次点兵（第" + (activeMatch + 1) + "场）");

        @SuppressWarnings("unchecked")
        Map<String, Object> teamConfig = (Map<String, Object>) config.get("teamConfig");
        if (teamConfig == null) throw new RuntimeException("队伍配置不存在");

        @SuppressWarnings("unchecked")
        Map<String, Object> teamA = (Map<String, Object>) teamConfig.get("teamA");
        @SuppressWarnings("unchecked")
        Map<String, Object> teamB = (Map<String, Object>) teamConfig.get("teamB");

        Long captainAId = ((Number) teamA.get("captainUserId")).longValue();
        Long captainBId = ((Number) teamB.get("captainUserId")).longValue();
        String teamAName = (String) teamA.getOrDefault("name", "薯家军");
        String teamBName = (String) teamB.getOrDefault("name", "嘟家军");

        // 校验是否是队长
        if (!userId.equals(captainAId) && !userId.equals(captainBId)) {
            throw new RuntimeException("只有将领才能进入点兵");
        }

        // 获取队长名字
        final String captainAName = userRepository.findById(captainAId).map(User::getNickname).orElse("将领A");
        final String captainBName = userRepository.findById(captainBId).map(User::getNickname).orElse("将领B");

        // 创建或获取会话（从 DB 恢复已有 pick）
        DraftSession session = sessions.computeIfAbsent(matchIndex, idx -> {
            DraftSession s = new DraftSession(idx, activityId,
                    teamAName, teamBName, captainAId, captainBId, captainAName, captainBName);

            // 加载可选玩家池
            List<PlayerInfo> allPlayers = loadAvailablePlayers(activityId, idx, captainAId, captainBId);

            // 从 DB 恢复已有 pick
            List<DraftPick> existingPicks = draftPickRepository
                    .findByActivityIdAndMatchIndexOrderByPickOrderAsc(activityId, idx);

            Set<Long> pickedUserIds = new HashSet<>();
            for (DraftPick pick : existingPicks) {
                PlayerInfo pi = new PlayerInfo(pick.getUserId(), pick.getUserName(), pick.getJob());
                pickedUserIds.add(pick.getUserId());
                if ("A".equals(pick.getTeamSide())) s.pickedA.add(pi);
                else s.pickedB.add(pi);
            }

            // 可选池 = 全部报名玩家 - 已被选的
            for (PlayerInfo p : allPlayers) {
                if (!pickedUserIds.contains(p.userId())) {
                    s.availablePlayers.add(p);
                }
            }

            // 恢复状态
            if (s.isComplete() || s.availablePlayers.isEmpty()) {
                s.status = "COMPLETED";
                s.currentTurn = null;
                log.info("[点兵] 第{}场从DB恢复为COMPLETED，A:{}人 B:{}人",
                        idx + 1, s.pickedA.size(), s.pickedB.size());
            } else if (!existingPicks.isEmpty()) {
                s.status = "IN_PROGRESS";
                // 恢复 currentTurn：谁选的少谁先选（或根据先手规则）
                s.currentTurn = determineCurrentTurn(s, idx, config);
                log.info("[点兵] 第{}场从DB恢复IN_PROGRESS，A:{}人 B:{}人，当前轮到{}",
                        idx + 1, s.pickedA.size(), s.pickedB.size(), s.currentTurn);
            } else {
                s.currentTurn = determineFirstPicker(idx, config);
                log.info("[点兵] 第{}场新建会话，先手={}，可选玩家{}人",
                        idx + 1, s.currentTurn, s.availablePlayers.size());
            }
            return s;
        });

        // 校验会话归属
        if (!session.activityId.equals(activityId)) {
            throw new RuntimeException("会话不属于当前活动");
        }
        if (!session.isCaptain(userId)) {
            throw new RuntimeException("只有将领才能进入点兵");
        }
        // COMPLETED 状态允许连接查看名单，不抛异常

        // 创建 SSE 连接
        SseEmitter emitter = new SseEmitter(1_800_000L); // 30分钟超时
        session.emitters.put(userId, emitter);

        // 注册清理回调
        Runnable cleanup = () -> {
            session.emitters.remove(userId);
            log.info("[点兵] 将领 {} 断开第{}场连接", userId, matchIndex + 1);
            // 有 pick 记录的会话保留在内存中（支持从 DB 恢复）
            if (session.emitters.isEmpty() && !"COMPLETED".equals(session.status)
                    && session.pickedA.isEmpty() && session.pickedB.isEmpty()) {
                sessions.remove(matchIndex);
                log.info("[点兵] 第{}场会话已清理（无连接、无pick）", matchIndex + 1);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        log.info("[点兵] 将领 {} 加入第{}场会话（{}）", userId, matchIndex + 1,
                userId.equals(captainAId) ? teamAName : teamBName);

        // 通知所有连接者
        sendEvent(session, "CAPTAIN_JOINED", Map.of(
                "userId", userId,
                "side", session.getSide(userId),
                "connectedCount", session.emitters.size()
        ));

        // 如果双方都已连接 → 开始点兵
        if (session.emitters.size() >= 2 && "WAITING".equals(session.status)) {
            session.status = "BOTH_READY";
            sendEvent(session, "BOTH_READY", buildFullState(session));
            log.info("[点兵] 第{}场双方将领就绪，即将开始点兵", matchIndex + 1);
        } else if ("IN_PROGRESS".equals(session.status) || "BOTH_READY".equals(session.status)) {
            // 重连场景：推送当前状态
            sendToEmitter(emitter, "STATE_SYNC", buildFullState(session));
        } else if ("COMPLETED".equals(session.status)) {
            // 已完成：推送最终名单状态
            sendToEmitter(emitter, "DRAFT_COMPLETE", buildFullState(session));
        }

        return emitter;
    }

    /**
     * 将领选人
     */
    public Map<String, Object> submitPick(int matchIndex, Long activityId, Long userId, Long playerUserId) {
        DraftSession session = sessions.get(matchIndex);
        if (session == null) throw new RuntimeException("点兵会话不存在");
        if (!session.activityId.equals(activityId)) throw new RuntimeException("会话不匹配");
        if (!"IN_PROGRESS".equals(session.status) && !"BOTH_READY".equals(session.status)) {
            throw new RuntimeException("点兵尚未开始或已结束");
        }

        String side = session.getSide(userId);
        if (side == null) throw new RuntimeException("你不是将领");
        if (!side.equals(session.currentTurn)) throw new RuntimeException("还没轮到你选人");
        if (session.isFull(side)) throw new RuntimeException("你的队伍已选满");

        // 查找玩家
        PlayerInfo player = session.availablePlayers.stream()
                .filter(p -> p.userId().equals(playerUserId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("该玩家不在可选池中"));

        // 选人
        session.availablePlayers.remove(player);
        if ("A".equals(side)) session.pickedA.add(player);
        else session.pickedB.add(player);

        // 持久化到 DB
        int pickOrder = "A".equals(side) ? session.pickedA.size() : session.pickedB.size();
        DraftPick pick = DraftPick.builder()
                .activityId(activityId)
                .matchIndex(matchIndex)
                .userId(player.userId())
                .userName(player.nickname())
                .teamSide(side)
                .job(player.job() != null ? player.job() : "")
                .pickOrder(pickOrder)
                .build();
        draftPickRepository.save(pick);

        // 记录选人日志
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("side", side);
        logEntry.put("playerUserId", player.userId());
        logEntry.put("playerName", player.nickname());
        logEntry.put("job", player.job());
        logEntry.put("pickOrder", ("A".equals(side) ? session.pickedA.size() : session.pickedB.size()));
        session.draftLog.add(logEntry);

        // 切换回合（如果对方还没选满）
        String otherSide = "A".equals(side) ? "B" : "A";
        if (!session.isFull(otherSide)) {
            session.currentTurn = otherSide;
        } else if (!session.isFull(side)) {
            session.currentTurn = side; // 对方满了自己继续选
        }

        log.info("[点兵] 第{}场 {} 选了 {} ({})，A:{}人 B:{}人",
                matchIndex + 1, side, player.nickname(), player.job(),
                session.pickedA.size(), session.pickedB.size());

        // 检查是否全部选完
        if (session.isComplete()) {
            session.status = "COMPLETED";
            sendEvent(session, "DRAFT_COMPLETE", buildFullState(session));
            log.info("[点兵] 第{}场点兵完成！", matchIndex + 1);
        } else {
            // 如果是BOTH_READY的第一次选人，切换到IN_PROGRESS
            if ("BOTH_READY".equals(session.status)) {
                session.status = "IN_PROGRESS";
            }
            sendEvent(session, "PICK_MADE", buildFullState(session));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("picked", Map.of(
                "userId", player.userId(),
                "nickname", player.nickname(),
                "job", player.job() != null ? player.job() : ""
        ));
        result.put("side", side);
        result.put("currentTurn", session.currentTurn);
        return result;
    }

    /**
     * 将领主动离开点兵
     */
    public void leaveSession(int matchIndex, Long userId) {
        DraftSession session = sessions.get(matchIndex);
        if (session == null) return;
        SseEmitter emitter = session.emitters.remove(userId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        sendEvent(session, "CAPTAIN_LEFT", Map.of("userId", userId, "side", session.getSide(userId)));
        log.info("[点兵] 将领 {} 离开第{}场", userId, matchIndex + 1);
    }

    /**
     * 获取当前会话状态（用于前端重连恢复）
     * 无内存会话时从 DB 读取
     */
    public Map<String, Object> getSessionState(int matchIndex, Long activityId) {
        DraftSession session = sessions.get(matchIndex);
        if (session != null) {
            return buildFullState(session);
        }
        // 从 DB 查询
        return loadStateFromDb(matchIndex, activityId);
    }

    /**
     * 获取某场比赛的最终名单（点兵完成后查看）
     */
    public Map<String, Object> getRoster(int matchIndex, Long activityId) {
        return loadStateFromDb(matchIndex, activityId);
    }

    /**
     * 获取当前可点兵的 matchIndex（根据活动阶段）
     * @return matchIndex (0-3) 或 -1 表示不在点兵阶段
     */
    public int getActiveMatchIndex(Long activityId) {
        Map<String, Object> config = loadConfig(activityId);
        return getActiveMatchIndex(config);
    }

    /**
     * 获取某场比赛的点兵状态摘要（用于前端按钮显示）
     */
    public Map<String, Object> getSessionSummary(int matchIndex, Long activityId) {
        DraftSession session = sessions.get(matchIndex);
        if (session != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", session.status);
            summary.put("connectedCount", session.emitters.size());
            summary.put("pickedACount", session.pickedA.size());
            summary.put("pickedBCount", session.pickedB.size());
            summary.put("currentTurn", session.currentTurn);
            return summary;
        }
        // 从 DB 查
        long countA = draftPickRepository.countByActivityIdAndMatchIndexAndTeamSide(activityId, matchIndex, "A");
        long countB = draftPickRepository.countByActivityIdAndMatchIndexAndTeamSide(activityId, matchIndex, "B");
        if (countA == 0 && countB == 0) return Map.of("status", "NO_SESSION");

        // 判断完成状态：双方满员 或 所有可选玩家均已被选
        int roundId = matchIndex < 2 ? 1 : 2;
        long totalRegistered = registrationRepository.countByActivityIdAndRoundId(activityId, roundId);
        long totalAvailable = Math.max(0, totalRegistered - 2);
        long totalPicked = countA + countB;
        boolean complete = (countA >= PICKS_PER_TEAM && countB >= PICKS_PER_TEAM)
                || totalPicked >= totalAvailable;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", complete ? "COMPLETED" : "IN_PROGRESS");
        summary.put("pickedACount", countA);
        summary.put("pickedBCount", countB);
        return summary;
    }

    // ==================== 内部方法 ====================

    /** 加载可选玩家池（该轮报名玩家，排除将领） */
    private List<PlayerInfo> loadAvailablePlayers(Long activityId, int matchIndex,
                                                   Long captainAId, Long captainBId) {
        int roundId = matchIndex < 2 ? 1 : 2;
        List<PvpRegistration> regs = registrationRepository
                .findByActivityIdAndRoundIdWithUser(activityId, roundId);

        List<PlayerInfo> players = new ArrayList<>();
        for (PvpRegistration reg : regs) {
            Long uid = reg.getUser().getId();
            // 排除将领自身
            if (uid.equals(captainAId) || uid.equals(captainBId)) continue;
            String nickname = reg.getUser().getNickname();
            String job = reg.getJob() != null ? reg.getJob() : "";
            players.add(new PlayerInfo(uid, nickname, job));
        }
        return players;
    }

    /** 确定先手方 */
    @SuppressWarnings("unchecked")
    private String determineFirstPicker(int matchIndex, Map<String, Object> config) {
        if (matchIndex == 0) return "B"; // 第一场嘟家军先手

        // 读取上一场的结果
        List<Map<String, Object>> matchHistory = (List<Map<String, Object>>)
                config.getOrDefault("matchHistory", Collections.emptyList());
        if (matchIndex - 1 < matchHistory.size()) {
            Map<String, Object> prevMatch = matchHistory.get(matchIndex - 1);
            String winner = (String) prevMatch.get("winner");
            if ("A".equals(winner)) return "B"; // 败方先手
            if ("B".equals(winner)) return "A";
        }
        return "B"; // 默认嘟家军先手
    }

    /** 构建完整状态推送 */
    private Map<String, Object> buildFullState(DraftSession session) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("matchIndex", session.matchIndex);
        state.put("status", session.status);
        state.put("currentTurn", session.currentTurn);
        state.put("teamAName", session.teamAName);
        state.put("teamBName", session.teamBName);
        state.put("captainAName", session.captainAName);
        state.put("captainBName", session.captainBName);
        state.put("captainAUserId", session.captainAUserId);
        state.put("captainBUserId", session.captainBUserId);
        state.put("picksPerTeam", PICKS_PER_TEAM);

        state.put("pickedA", session.pickedA.stream()
                .map(p -> Map.of("userId", p.userId(), "nickname", p.nickname(),
                        "job", p.job() != null ? p.job() : ""))
                .collect(Collectors.toList()));
        state.put("pickedB", session.pickedB.stream()
                .map(p -> Map.of("userId", p.userId(), "nickname", p.nickname(),
                        "job", p.job() != null ? p.job() : ""))
                .collect(Collectors.toList()));
        state.put("available", session.availablePlayers.stream()
                .map(p -> Map.of("userId", p.userId(), "nickname", p.nickname(),
                        "job", p.job() != null ? p.job() : ""))
                .collect(Collectors.toList()));
        return state;
    }

    /** 向会话中所有连接推送事件 */
    private void sendEvent(DraftSession session, String eventName, Object data) {
        List<Long> deadConnections = new ArrayList<>();
        for (Map.Entry<Long, SseEmitter> entry : session.emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (IOException e) {
                deadConnections.add(entry.getKey());
            }
        }
        // 清理断开的连接
        for (Long uid : deadConnections) {
            session.emitters.remove(uid);
        }
    }

    /** 向单个 emitter 推送事件 */
    private void sendToEmitter(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException ignored) {}
    }

    /** 加载活动配置 */
    private Map<String, Object> loadConfig(Long activityId) {
        try {
            ActivityConfig config = configRepository.findByActivityId(activityId).orElse(null);
            if (config == null) return new LinkedHashMap<>();
            return objectMapper.readValue(config.getConfigJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[点兵] 读取配置失败", e);
            return new LinkedHashMap<>();
        }
    }

    /** 获取当前活跃阶段对应的 matchIndex */
    @SuppressWarnings("unchecked")
    private int getActiveMatchIndex(Map<String, Object> config) {
        // 优先使用 draftTimeline（点兵专用时间），否则回退到 timeline
        List<Map<String, Object>> schedule = (List<Map<String, Object>>)
                config.getOrDefault("draftTimeline",
                        config.getOrDefault("timeline", Collections.emptyList()));
        long now = System.currentTimeMillis();
        for (Map<String, Object> step : schedule) {
            String phase = (String) step.get("phase");
            if (phase != null && PHASE_TO_MATCH.containsKey(phase)) {
                // 检查时间范围
                try {
                    String startStr = (String) step.get("start");
                    String endStr = (String) step.get("end");
                    if (startStr != null && endStr != null) {
                        long start = java.time.LocalDateTime.parse(startStr)
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                        long end = java.time.LocalDateTime.parse(endStr)
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                        if (now >= start && now <= end) {
                            return PHASE_TO_MATCH.get(phase);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return -1;
    }

    /** 从 DB 加载某场比赛的点兵状态 */
    private Map<String, Object> loadStateFromDb(int matchIndex, Long activityId) {
        List<DraftPick> picks = draftPickRepository
                .findByActivityIdAndMatchIndexOrderByPickOrderAsc(activityId, matchIndex);
        if (picks.isEmpty()) {
            return Map.of("status", "NO_SESSION");
        }

        // 加载配置获取队伍名和将领信息
        Map<String, Object> config = loadConfig(activityId);
        @SuppressWarnings("unchecked")
        Map<String, Object> teamConfig = (Map<String, Object>) config.getOrDefault("teamConfig", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> teamA = (Map<String, Object>) teamConfig.getOrDefault("teamA", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> teamB = (Map<String, Object>) teamConfig.getOrDefault("teamB", Map.of());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("matchIndex", matchIndex);
        state.put("teamAName", teamA.getOrDefault("name", "薯家军"));
        state.put("teamBName", teamB.getOrDefault("name", "嘟家军"));
        state.put("captainAName", teamA.getOrDefault("captainName", "将领A"));
        state.put("captainBName", teamB.getOrDefault("captainName", "将领B"));
        state.put("picksPerTeam", PICKS_PER_TEAM);
        state.put("available", Collections.emptyList());

        List<Map<String, Object>> pickedA = new ArrayList<>();
        List<Map<String, Object>> pickedB = new ArrayList<>();

        // 添加将领到名单（将领不在 draft_pick 中，需从配置和报名表获取）
        if (teamA.get("captainUserId") instanceof Number && teamB.get("captainUserId") instanceof Number) {
            Long captainAId = ((Number) teamA.get("captainUserId")).longValue();
            Long captainBId = ((Number) teamB.get("captainUserId")).longValue();
            String captainAName = userRepository.findById(captainAId).map(User::getNickname).orElse("将领A");
            String captainBName = userRepository.findById(captainBId).map(User::getNickname).orElse("将领B");
            int roundId = matchIndex < 2 ? 1 : 2;

            // 查将领职业
            String captainAJob = registrationRepository.findByActivityIdAndUserIdAndRoundId(activityId, captainAId, roundId)
                    .map(r -> r.getJob() != null ? r.getJob() : "").orElse("");
            String captainBJob = registrationRepository.findByActivityIdAndUserIdAndRoundId(activityId, captainBId, roundId)
                    .map(r -> r.getJob() != null ? r.getJob() : "").orElse("");

            Map<String, Object> capA = new LinkedHashMap<>();
            capA.put("userId", captainAId);
            capA.put("nickname", captainAName);
            capA.put("job", captainAJob);
            capA.put("isCaptain", true);
            pickedA.add(capA);

            Map<String, Object> capB = new LinkedHashMap<>();
            capB.put("userId", captainBId);
            capB.put("nickname", captainBName);
            capB.put("job", captainBJob);
            capB.put("isCaptain", true);
            pickedB.add(capB);
        }

        for (DraftPick p : picks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", p.getUserId());
            item.put("nickname", p.getUserName());
            item.put("job", p.getJob() != null ? p.getJob() : "");
            if ("A".equals(p.getTeamSide())) pickedA.add(item);
            else pickedB.add(item);
        }
        state.put("pickedA", pickedA);
        state.put("pickedB", pickedB);

        // 判断是否完成：双方满员 或 所有报名玩家均已被选
        int roundId = matchIndex < 2 ? 1 : 2;
        long totalRegistered = registrationRepository.countByActivityIdAndRoundId(activityId, roundId);
        // 减去2个将领（将领自动报名但不在可选池中）
        long totalAvailable = Math.max(0, totalRegistered - 2);
        long totalPicked = pickedA.size() + pickedB.size();
        boolean complete = (pickedA.size() >= PICKS_PER_TEAM && pickedB.size() >= PICKS_PER_TEAM)
                || totalPicked >= totalAvailable;
        state.put("status", complete ? "COMPLETED" : "IN_PROGRESS");
        state.put("currentTurn", null);
        return state;
    }

    /** 从 DB 恢复时推断当前轮到谁选 */
    private String determineCurrentTurn(DraftSession session, int matchIndex, Map<String, Object> config) {
        // 选得少的那方先选；如果一样多，按先手规则
        if (session.pickedA.size() < session.pickedB.size()) return "A";
        if (session.pickedB.size() < session.pickedA.size()) return "B";
        // 数量相同，按先手规则
        return determineFirstPicker(matchIndex, config);
    }
}
