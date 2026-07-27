package com.potato.peacehaven.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 南希对抗赛 - 赛事服务
 * 负责报名、选秀、赛程管理、战绩、排行和荣誉
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NancyDraftService {

    private final PvpRegistrationRepository registrationRepository;
    private final NancyDraftTeamRepository draftTeamRepository;
    private final ActivityConfigRepository configRepository;
    private final ActivityJudgeRepository judgeRepository;
    private final DraftBattleRecordRepository battleRecordRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每队人数上限 */
    public static final int TEAM_SIZE = 12;

    // ==================== 报名 ====================

    /**
     * 报名参赛（按轮次）
     * @param roundId 轮次ID（1=第1、2场，2=第3、4场）
     */
    @Transactional
    public Map<String, Object> register(Long activityId, User user, int roundId) {
        if (registrationRepository.existsByActivityIdAndUserIdAndRoundId(activityId, user.getId(), roundId)) {
            throw new RuntimeException("你已经报名了（第" + roundId + "轮）");
        }

        PvpRegistration reg = PvpRegistration.builder()
                .activityId(activityId)
                .user(user)
                .roundId(roundId)
                .build();
        registrationRepository.save(reg);

        long total = registrationRepository.countByActivityIdAndRoundId(activityId, roundId);
        log.info("[南希对抗赛] 用户 {} 报名第{}轮 (当前{}人)", user.getNickname(), roundId, total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("totalRegistered", total);
        result.put("roundId", roundId);
        return result;
    }

    /**
     * 取消报名（按轮次）
     */
    @Transactional
    public Map<String, Object> cancelRegistration(Long activityId, User user, int roundId) {
        var reg = registrationRepository.findByActivityIdAndUserIdAndRoundId(activityId, user.getId(), roundId);
        if (reg.isEmpty()) {
            throw new RuntimeException("你还没有报名（第" + roundId + "轮）");
        }
        registrationRepository.delete(reg.get());
        registrationRepository.flush();

        long total = registrationRepository.countByActivityIdAndRoundId(activityId, roundId);
        log.info("[南希对抗赛] 用户 {} 取消报名第{}轮 (当前{}人)", user.getNickname(), roundId, total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("totalRegistered", total);
        result.put("roundId", roundId);
        return result;
    }

    // ==================== 选秀（双将选人） ====================

    /**
     * 初始化选秀 - 设置两名队长
     */
    @Transactional
    public void initDraft(Long activityId, User captainA, User captainB,
                          String teamAName, String teamBName) {
        if (draftTeamRepository.countByActivityId(activityId) > 0) {
            log.warn("[南希对抗赛] 选秀队伍已存在，跳过初始化");
            return;
        }

        NancyDraftTeam teamA = NancyDraftTeam.builder()
                .activityId(activityId)
                .teamSide("A")
                .teamName(teamAName != null ? teamAName : "薯家军")
                .captainUserId(captainA.getId())
                .captainName(captainA.getNickname())
                .captainAvatar(captainA.getAvatar())
                .memberJson("[]")
                .memberCount(0)
                .status("WAITING")
                .build();

        NancyDraftTeam teamB = NancyDraftTeam.builder()
                .activityId(activityId)
                .teamSide("B")
                .teamName(teamBName != null ? teamBName : "嘟家军")
                .captainUserId(captainB.getId())
                .captainName(captainB.getNickname())
                .captainAvatar(captainB.getAvatar())
                .memberJson("[]")
                .memberCount(0)
                .status("WAITING")
                .build();

        draftTeamRepository.saveAll(Arrays.asList(teamA, teamB));
        log.info("[南希对抗赛] 选秀初始化完成: {}({}) vs {}({})",
                teamAName, captainA.getNickname(), teamBName, captainB.getNickname());
    }

    /**
     * 队长选人
     * @param activityId 活动ID
     * @param teamSide A/B
     * @param playerUserId 被选玩家ID
     * @param currentPlayer 操作者（需是队长）
     */
    @Transactional
    public Map<String, Object> submitPick(Long activityId, String teamSide,
                                           Long playerUserId, User currentPlayer) {
        NancyDraftTeam team = draftTeamRepository.findByActivityIdAndTeamSide(activityId, teamSide)
                .orElseThrow(() -> new RuntimeException("队伍不存在: " + teamSide));

        // 校验队长身份
        if (!team.getCaptainUserId().equals(currentPlayer.getId())) {
            throw new RuntimeException("只有队长才能选人");
        }
        if ("COMPLETED".equals(team.getStatus())) {
            throw new RuntimeException("选秀已结束");
        }
        if (team.getMemberCount() >= TEAM_SIZE) {
            throw new RuntimeException("队伍已满 " + TEAM_SIZE + " 人");
        }

        // 检查是否已被任一队选走
        NancyDraftTeam otherSide = teamSide.equals("A")
                ? draftTeamRepository.findByActivityIdAndTeamSide(activityId, "B").orElse(null)
                : draftTeamRepository.findByActivityIdAndTeamSide(activityId, "A").orElse(null);

        if (isPlayerPicked(team, playerUserId) || (otherSide != null && isPlayerPicked(otherSide, playerUserId))) {
            throw new RuntimeException("该选手已被选走");
        }

        // 获取被选玩家信息
        PvpRegistration reg = registrationRepository.findByActivityIdAndUserId(activityId, playerUserId)
                .orElseThrow(() -> new RuntimeException("该选手未报名"));

        // 添加到队员列表
        try {
            List<Map<String, Object>> members = parseMembers(team.getMemberJson());
            Map<String, Object> newMember = new LinkedHashMap<>();
            newMember.put("userId", reg.getUser().getId());
            newMember.put("nickname", reg.getUser().getNickname());
            newMember.put("avatar", reg.getUser().getAvatar());
            newMember.put("pickOrder", team.getMemberCount() + 1);
            members.add(newMember);

            team.setMemberJson(objectMapper.writeValueAsString(members));
            team.setMemberCount(members.size());
            if (members.size() >= TEAM_SIZE) {
                team.setStatus("COMPLETED");
            } else {
                team.setStatus("DRAFTING");
            }
            draftTeamRepository.save(team);

            log.info("[南希对抗赛] {} 选了 {} (第{}人)",
                    team.getTeamName(), reg.getUser().getNickname(), team.getMemberCount());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("teamSide", teamSide);
            result.put("memberCount", team.getMemberCount());
            result.put("picked", newMember);
            return result;

        } catch (Exception e) {
            throw new RuntimeException("选人失败: " + e.getMessage());
        }
    }

    private boolean isPlayerPicked(NancyDraftTeam team, Long playerUserId) {
        try {
            List<Map<String, Object>> members = parseMembers(team.getMemberJson());
            return members.stream().anyMatch(m ->
                    playerUserId.equals(((Number) m.get("userId")).longValue()));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 赛事状态 ====================

    /**
     * 获取赛事完整状态
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getEventStatus(Long activityId, User currentUser) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 报名统计（按轮次）
        long totalR1 = registrationRepository.countByActivityIdAndRoundId(activityId, 1);
        long totalR2 = registrationRepository.countByActivityIdAndRoundId(activityId, 2);
        result.put("totalRegistered", totalR1);
        result.put("totalRegisteredR1", totalR1);
        result.put("totalRegisteredR2", totalR2);

        // 用户报名状态（按轮次）
        boolean isRegisteredR1 = false, isRegisteredR2 = false;
        if (currentUser != null) {
            isRegisteredR1 = registrationRepository.existsByActivityIdAndUserIdAndRoundId(activityId, currentUser.getId(), 1);
            isRegisteredR2 = registrationRepository.existsByActivityIdAndUserIdAndRoundId(activityId, currentUser.getId(), 2);
        }
        result.put("isRegistered", isRegisteredR1);
        result.put("isRegisteredR1", isRegisteredR1);
        result.put("isRegisteredR2", isRegisteredR2);

        // 队伍/选秀状态
        List<NancyDraftTeam> teams = draftTeamRepository.findByActivityIdOrderByTeamSideAsc(activityId);
        List<Map<String, Object>> teamData = new ArrayList<>();
        for (NancyDraftTeam t : teams) {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("teamSide", t.getTeamSide());
            td.put("teamName", t.getTeamName());
            td.put("captainUserId", t.getCaptainUserId());
            td.put("captainName", t.getCaptainName());
            td.put("captainAvatar", t.getCaptainAvatar());
            td.put("memberCount", t.getMemberCount());
            td.put("status", t.getStatus());
            try {
                td.put("members", parseMembers(t.getMemberJson()));
            } catch (Exception e) {
                td.put("members", Collections.emptyList());
            }
            teamData.add(td);
        }
        result.put("teams", teamData);

        // 选秀阶段判断
        String draftPhase = "WAITING";
        if (!teams.isEmpty()) {
            boolean allDone = teams.stream().allMatch(t -> "COMPLETED".equals(t.getStatus()));
            boolean anyStarted = teams.stream().anyMatch(t ->
                    "DRAFTING".equals(t.getStatus()) || "COMPLETED".equals(t.getStatus()));
            if (allDone) draftPhase = "COMPLETED";
            else if (anyStarted) draftPhase = "DRAFTING";
        }
        result.put("draftPhase", draftPhase);

        // 是否是队长
        String myTeamSide = null;
        if (currentUser != null) {
            for (NancyDraftTeam t : teams) {
                if (currentUser.getId().equals(t.getCaptainUserId())) {
                    myTeamSide = t.getTeamSide();
                    break;
                }
            }
        }
        result.put("myTeamSide", myTeamSide);

        // 裁判身份
        boolean isJudge = currentUser != null
                && judgeRepository.existsByActivityIdAndUserId(activityId, currentUser.getId());
        result.put("isJudge", isJudge);

        // 从 configJson 读取赛程、荣誉
        Map<String, Object> configMap = loadConfig(activityId);
        result.put("schedule", configMap.getOrDefault("schedule", Collections.emptyList()));
        result.put("matchHistory", configMap.getOrDefault("matchHistory", Collections.emptyList()));
        result.put("honors", configMap.getOrDefault("honors", Collections.emptyList()));
        result.put("timeline", configMap.getOrDefault("timeline", Collections.emptyList()));

        // 战绩排行榜（从 DB 实时计算）
        result.put("rankings", buildRankings(activityId));

        // 队长信息（从 configJson 的 teamConfig 读取）
        result.put("judges", buildCaptainsFromConfig(configMap));

        // 当前用户是否是将领
        boolean isCaptain = false;
        if (currentUser != null) {
            isCaptain = isUserCaptain(configMap, currentUser.getId());
        }
        result.put("isCaptain", isCaptain);

        // 已报名玩家列表（用于气泡展示）
        List<PvpRegistration> allRegs = registrationRepository.findByActivityIdOrderByPointsDescWinsDesc(activityId);
        Set<Long> pickedUserIds = new HashSet<>();
        for (NancyDraftTeam t : teams) {
            if (t.getCaptainUserId() != null) pickedUserIds.add(t.getCaptainUserId());
            try {
                List<Map<String, Object>> members = parseMembers(t.getMemberJson());
                for (Map<String, Object> mem : members) {
                    pickedUserIds.add(((Number) mem.get("userId")).longValue());
                }
            } catch (Exception ignored) {}
        }
        List<Map<String, Object>> registeredPlayers = new ArrayList<>();
        for (PvpRegistration r : allRegs) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("nickname", r.getUser().getNickname());
            p.put("roundId", r.getRoundId());
            p.put("picked", pickedUserIds.contains(r.getUser().getId()));
            registeredPlayers.add(p);
        }
        result.put("registeredPlayers", registeredPlayers);

        return result;
    }

    /**
     * 获取选秀状态
     */
    public Map<String, Object> getDraftStatus(Long activityId) {
        List<NancyDraftTeam> teams = draftTeamRepository.findByActivityIdOrderByTeamSideAsc(activityId);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> teamData = new ArrayList<>();
        for (NancyDraftTeam t : teams) {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("teamSide", t.getTeamSide());
            td.put("teamName", t.getTeamName());
            td.put("captainName", t.getCaptainName());
            td.put("captainAvatar", t.getCaptainAvatar());
            td.put("memberCount", t.getMemberCount());
            td.put("status", t.getStatus());
            try {
                td.put("members", parseMembers(t.getMemberJson()));
            } catch (Exception e) {
                td.put("members", Collections.emptyList());
            }
            teamData.add(td);
        }
        result.put("teams", teamData);

        // 报名选手列表（未被选的）
        List<PvpRegistration> allRegs = registrationRepository.findByActivityIdOrderByPointsDescWinsDesc(activityId);
        Set<Long> pickedIds = new HashSet<>();
        for (NancyDraftTeam t : teams) {
            try {
                List<Map<String, Object>> members = parseMembers(t.getMemberJson());
                for (Map<String, Object> m : members) {
                    pickedIds.add(((Number) m.get("userId")).longValue());
                }
                // 队长也算已选
                if (t.getCaptainUserId() != null) pickedIds.add(t.getCaptainUserId());
            } catch (Exception ignored) {}
        }

        List<Map<String, Object>> available = new ArrayList<>();
        for (PvpRegistration r : allRegs) {
            if (!pickedIds.contains(r.getUser().getId())) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("userId", r.getUser().getId());
                p.put("nickname", r.getUser().getNickname());
                p.put("avatar", r.getUser().getAvatar());
                available.add(p);
            }
        }
        result.put("availablePlayers", available);

        return result;
    }

    // ==================== 战绩录入与排行榜 ====================

    /**
     * 录入单条战绩记录（裁判/管理员）
     */
    @Transactional
    public Map<String, Object> submitBattleRecord(Long activityId, Long userId, String userName,
                                                   int gameId, String team, int kills, int deaths,
                                                   int assists, long damage, String job, String result) {
        // 校验
        if (gameId < 1 || gameId > 4) throw new RuntimeException("gameId 必须在 1-4 之间");
        if (!"teamA".equals(team) && !"teamB".equals(team)) throw new RuntimeException("team 必须为 teamA 或 teamB");
        if (!"步枪兵".equals(job) && !"狙击手".equals(job) && !"武士".equals(job)) throw new RuntimeException("job 无效");
        if (!"WIN".equals(result) && !"LOSS".equals(result)) throw new RuntimeException("result 必须为 WIN 或 LOSS");

        // 查找已有记录或新建
        List<DraftBattleRecord> existing = battleRecordRepository.findByActivityIdAndGameId(activityId, gameId);
        DraftBattleRecord record = existing.stream()
                .filter(r -> r.getUserId().equals(userId))
                .findFirst()
                .orElse(DraftBattleRecord.builder()
                        .activityId(activityId)
                        .userId(userId)
                        .gameId(gameId)
                        .build());

        record.setUserName(userName);
        record.setTeam(team);
        record.setKills(kills);
        record.setDeaths(deaths);
        record.setAssists(assists);
        record.setDamage(damage);
        record.setJob(job);
        record.setResult(result);
        record.calculateKda();

        battleRecordRepository.save(record);
        log.info("[南希对抗赛] 录入战绩: {} 第{}场 K={} D={} A={} KDA={}",
                userName, gameId, kills, deaths, assists, record.getKda());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("recordId", record.getId());
        resp.put("kda", record.getKda());
        return resp;
    }

    /**
     * 批量录入战绩（一场比赛所有玩家）
     */
    @Transactional
    public Map<String, Object> submitBatchRecords(Long activityId, int gameId,
                                                    List<Map<String, Object>> players) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> p : players) {
            Long userId = Long.valueOf(p.get("userId").toString());
            String userName = (String) p.getOrDefault("userName", "");
            String team = (String) p.get("team");
            int kills = Integer.parseInt(p.getOrDefault("kills", 0).toString());
            int deaths = Integer.parseInt(p.getOrDefault("deaths", 0).toString());
            int assists = Integer.parseInt(p.getOrDefault("assists", 0).toString());
            long damage = Long.parseLong(p.getOrDefault("damage", 0).toString());
            String job = (String) p.get("job");
            String result = (String) p.get("result");
            results.add(submitBattleRecord(activityId, userId, userName, gameId,
                    team, kills, deaths, assists, damage, job, result));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("gameId", gameId);
        resp.put("count", results.size());
        resp.put("records", results);
        return resp;
    }

    /**
     * 构建6个排行榜数据
     * 每个榜单：按 KDA 降序，每个用户只取最高 KDA 的一条记录
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildRankings(Long activityId) {
        List<DraftBattleRecord> allRecords = battleRecordRepository.findByActivityId(activityId);

        Map<String, Object> rankings = new LinkedHashMap<>();
        rankings.put("overall", buildRankList(allRecords));
        rankings.put("rifle", buildRankList(filterByJob(allRecords, "步枪兵")));
        rankings.put("sniper", buildRankList(filterByJob(allRecords, "狙击手")));
        rankings.put("warrior", buildRankList(filterByJob(allRecords, "武士")));
        rankings.put("teamA", buildRankList(filterByTeam(allRecords, "teamA")));
        rankings.put("teamB", buildRankList(filterByTeam(allRecords, "teamB")));
        return rankings;
    }

    private List<DraftBattleRecord> filterByJob(List<DraftBattleRecord> records, String job) {
        return records.stream().filter(r -> job.equals(r.getJob())).collect(Collectors.toList());
    }

    private List<DraftBattleRecord> filterByTeam(List<DraftBattleRecord> records, String team) {
        return records.stream().filter(r -> team.equals(r.getTeam())).collect(Collectors.toList());
    }

    /**
     * 从记录列表中构建排行榜：每个用户只保留最高 KDA 的记录，按 KDA 降序
     */
    private List<Map<String, Object>> buildRankList(List<DraftBattleRecord> records) {
        // 按 userId 分组，取每组最高 KDA
        Map<Long, DraftBattleRecord> bestByUser = new LinkedHashMap<>();
        for (DraftBattleRecord r : records) {
            DraftBattleRecord existing = bestByUser.get(r.getUserId());
            if (existing == null || r.getKda() > existing.getKda()) {
                bestByUser.put(r.getUserId(), r);
            }
        }

        // 按 KDA 降序排列
        List<DraftBattleRecord> sorted = new ArrayList<>(bestByUser.values());
        sorted.sort((a, b) -> Double.compare(b.getKda(), a.getKda()));

        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (DraftBattleRecord r : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("userId", r.getUserId());
            m.put("userName", r.getUserName());
            m.put("gameId", r.getGameId());
            m.put("team", r.getTeam());
            m.put("job", r.getJob());
            m.put("kills", r.getKills());
            m.put("deaths", r.getDeaths());
            m.put("assists", r.getAssists());
            m.put("damage", r.getDamage());
            m.put("result", r.getResult());
            m.put("kda", Math.round(r.getKda() * 100.0) / 100.0);
            result.add(m);
        }
        return result;
    }

    // ==================== 赛事数据管理（Config JSON） ====================

    /**
     * 提交比赛成绩（裁判）
     */
    @Transactional
    public Map<String, Object> submitMatchResult(Long activityId, int matchIndex,
                                                  int scoreA, int scoreB, User referee) {
        if (!judgeRepository.existsByActivityIdAndUserId(activityId, referee.getId())) {
            throw new RuntimeException("您不是裁判，无权提交成绩");
        }

        Map<String, Object> configMap = loadConfig(activityId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) configMap
                .getOrDefault("matchHistory", new ArrayList<>());

        if (matchIndex < 0 || matchIndex >= matches.size()) {
            throw new RuntimeException("比赛序号无效: " + matchIndex);
        }

        Map<String, Object> match = matches.get(matchIndex);
        match.put("scoreA", scoreA);
        match.put("scoreB", scoreB);
        match.put("winner", scoreA > scoreB ? "A" : (scoreB > scoreA ? "B" : "DRAW"));
        match.put("status", "COMPLETED");
        match.put("submittedAt", LocalDateTime.now().toString());

        configMap.put("matchHistory", matches);
        saveConfig(activityId, configMap);

        log.info("[南希对抗赛] 第{}场成绩: {}:{} (裁判:{})", matchIndex + 1, scoreA, scoreB, referee.getNickname());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("matchIndex", matchIndex);
        result.put("scoreA", scoreA);
        result.put("scoreB", scoreB);
        return result;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 configJson 的 teamConfig 构建队长列表（用于报名区展示）
     * configJson 结构示例：
     * "teamConfig": {
     *   "teamA": {"name": "薯家军", "captainUserId": 16},
     *   "teamB": {"name": "嘟家军", "captainUserId": 55}
     * }
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildCaptainsFromConfig(Map<String, Object> configMap) {
        List<Map<String, Object>> captains = new ArrayList<>();
        Map<String, Object> teamConfig = (Map<String, Object>) configMap.get("teamConfig");
        if (teamConfig == null) return captains;

        String[] sides = {"teamA", "teamB"};
        for (String side : sides) {
            Map<String, Object> team = (Map<String, Object>) teamConfig.get(side);
            if (team == null) continue;

            Number captainUserId = (Number) team.get("captainUserId");
            if (captainUserId == null) continue;

            userRepository.findById(captainUserId.longValue()).ifPresent(user -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", user.getNickname());
                m.put("roleTitle", team.getOrDefault("name", "队长"));
                m.put("avatar", user.getAvatar());
                captains.add(m);
            });
        }
        return captains;
    }

    /** 判断用户是否是将领（teamConfig 中任一队的 captainUserId 匹配） */
    @SuppressWarnings("unchecked")
    private boolean isUserCaptain(Map<String, Object> configMap, Long userId) {
        Map<String, Object> teamConfig = (Map<String, Object>) configMap.get("teamConfig");
        if (teamConfig == null) return false;
        for (Object val : teamConfig.values()) {
            Map<String, Object> team = (Map<String, Object>) val;
            if (team == null) continue;
            Number captainUserId = (Number) team.get("captainUserId");
            if (captainUserId != null && captainUserId.longValue() == userId) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig(Long activityId) {
        try {
            ActivityConfig config = configRepository.findByActivityId(activityId).orElse(null);
            if (config == null) return new LinkedHashMap<>();
            return objectMapper.readValue(config.getConfigJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[南希对抗赛] 读取配置失败", e);
            return new LinkedHashMap<>();
        }
    }

    private void saveConfig(Long activityId, Map<String, Object> configMap) {
        try {
            ActivityConfig config = configRepository.findByActivityId(activityId).orElse(null);
            if (config == null) {
                config = ActivityConfig.builder().activityId(activityId).configJson("{}").build();
            }
            config.setConfigJson(objectMapper.writeValueAsString(configMap));
            configRepository.save(config);
        } catch (Exception e) {
            log.error("[南希对抗赛] 保存配置失败", e);
            throw new RuntimeException("保存失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseMembers(String json) throws Exception {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
    }
}
