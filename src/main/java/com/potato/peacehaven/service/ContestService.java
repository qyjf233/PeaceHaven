package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.ActivityConfig;
import com.potato.peacehaven.entity.ContestAbstractVote;
import com.potato.peacehaven.entity.ContestJudgeScore;
import com.potato.peacehaven.entity.ContestVote;
import com.potato.peacehaven.entity.ContestWork;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.ContestPhase;
import com.potato.peacehaven.repository.ActivityConfigRepository;
import com.potato.peacehaven.repository.ActivityJudgeRepository;
import com.potato.peacehaven.repository.ContestAbstractVoteRepository;
import com.potato.peacehaven.repository.ContestJudgeScoreRepository;
import com.potato.peacehaven.repository.ContestVoteRepository;
import com.potato.peacehaven.repository.ContestWorkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContestService {

    private final ContestWorkRepository workRepository;
    private final ContestVoteRepository voteRepository;
    private final ContestAbstractVoteRepository abstractVoteRepository;
    private final ActivityConfigRepository configRepository;
    private final ActivityJudgeRepository judgeRepository;
    private final ContestJudgeScoreRepository judgeScoreRepository;

    /** 每人最多投票数 */
    public static final int MAX_VOTES_PER_USER = 3;

    /**
     * 投稿作品（受阶段控制）
     */
    @Transactional
    public ContestWork submitWork(Long activityId, User user, String title, String description, String imageUrl) {
        // 裁判不可投稿
        if (isJudge(activityId, user.getId())) {
            throw new RuntimeException("裁判不可投稿");
        }

        // 阶段检查
        ContestPhase phase = getCurrentPhase(activityId);
        if (phase != ContestPhase.SUBMISSION) {
            throw new RuntimeException(getPhaseRestrictionMessage(phase, "投稿"));
        }

        // 检查是否已投稿
        if (workRepository.findByActivityIdAndUserId(activityId, user.getId()).isPresent()) {
            throw new RuntimeException("你已经投稿过了，每位玩家仅限一次投稿");
        }

        ContestWork work = ContestWork.builder()
                .activityId(activityId)
                .user(user)
                .title(title)
                .description(description)
                .imageUrl(imageUrl)
                .build();

        return workRepository.save(work);
    }

    /**
     * 为作品投票（受阶段控制）
     */
    @Transactional
    public void voteForWork(Long workId, User user) {
        ContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        // 裁判不可投票
        if (isJudge(work.getActivityId(), user.getId())) {
            throw new RuntimeException("裁判不可投票");
        }

        // 阶段检查
        ContestPhase phase = getCurrentPhase(work.getActivityId());
        if (phase != ContestPhase.VOTING) {
            throw new RuntimeException(getPhaseRestrictionMessage(phase, "投票"));
        }

        if (work.getStatus() != ContestWork.WorkStatus.APPROVED) {
            throw new RuntimeException("该作品尚未通过审核");
        }

        if (voteRepository.existsByWorkIdAndUserId(workId, user.getId())) {
            throw new RuntimeException("你已经投过票了");
        }

        // 检查总投票数限制
        long totalVotes = getUserVoteCount(work.getActivityId(), user.getId());
        if (totalVotes >= MAX_VOTES_PER_USER) {
            throw new RuntimeException("每人最多只能投" + MAX_VOTES_PER_USER + "票，你的票数已用完");
        }

        // 记录投票
        ContestVote vote = ContestVote.builder()
                .work(work)
                .user(user)
                .build();
        voteRepository.save(vote);

        log.info("用户 {} 为作品 {} 投票，剩余票数: {}",
                user.getNickname(), work.getTitle(), MAX_VOTES_PER_USER - totalVotes - 1);
    }

    /**
     * 撤回投票
     */
    @Transactional
    public void retractVote(Long workId, User user) {
        ContestVote vote = voteRepository.findByWorkIdAndUserId(workId, user.getId())
                .orElseThrow(() -> new RuntimeException("你还没有对这个作品投票"));

        ContestWork work = vote.getWork();

        // 删除投票记录
        voteRepository.delete(vote);

        log.info("用户 {} 撤回了对作品 {} 的投票", user.getNickname(), work.getTitle());
    }

    /**
     * 查询已通过审核的作品列表（按票数降序）
     */
    public List<ContestWork> getApprovedWorks(Long activityId) {
        return workRepository.findByActivityIdAndStatus(
                activityId, ContestWork.WorkStatus.APPROVED);
    }

    /**
     * 查询用户在指定活动的已用票数
     */
    public long getUserVoteCount(Long activityId, Long userId) {
        return voteRepository.countByUserIdAndWorkActivityId(userId, activityId);
    }

    /**
     * 查询用户在指定活动的剩余票数
     */
    public int getRemainingVotes(Long activityId, Long userId) {
        long used = getUserVoteCount(activityId, userId);
        return Math.max(0, MAX_VOTES_PER_USER - (int) used);
    }

    /**
     * 检查用户是否已对某作品投票
     */
    public boolean hasVoted(Long workId, Long userId) {
        return voteRepository.existsByWorkIdAndUserId(workId, userId);
    }

    // ==============================
    // 抽象票（每人每活动限一票）
    // ==============================

    /**
     * 投抽象票：如果已投给其他作品则自动改投
     */
    @Transactional
    public void abstractVoteForWork(Long workId, User user) {
        ContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        Long activityId = work.getActivityId();

        // 裁判不可投票
        if (isJudge(activityId, user.getId())) {
            throw new RuntimeException("裁判不可投票");
        }

        // 阶段检查
        ContestPhase phase = getCurrentPhase(activityId);
        if (phase != ContestPhase.VOTING) {
            throw new RuntimeException(getPhaseRestrictionMessage(phase, "投票"));
        }

        if (work.getStatus() != ContestWork.WorkStatus.APPROVED) {
            throw new RuntimeException("该作品尚未通过审核");
        }

        // 检查是否已投给同一作品（取消操作应走撤回接口）
        var existingVote = abstractVoteRepository.findByActivityIdAndUserId(activityId, user.getId());
        if (existingVote.isPresent()) {
            if (existingVote.get().getWork().getId().equals(workId)) {
                throw new RuntimeException("你已经给这个作品投了抽象票");
            }
            // 已投给其他作品 → 改投：先撤回旧票
            ContestAbstractVote oldVote = existingVote.get();
            ContestWork oldWork = oldVote.getWork();
            abstractVoteRepository.delete(oldVote);
            abstractVoteRepository.flush();
            log.info("用户 {} 撤回对作品 {} 的抽象票，改投作品 {}", user.getNickname(), oldWork.getTitle(), work.getTitle());
        }

        // 投新票
        ContestAbstractVote newVote = ContestAbstractVote.builder()
                .activityId(activityId)
                .work(work)
                .user(user)
                .build();
        abstractVoteRepository.save(newVote);

        log.info("用户 {} 给作品 {} 投了抽象票", user.getNickname(), work.getTitle());
    }

    /**
     * 撤回抽象票
     */
    @Transactional
    public void retractAbstractVote(Long workId, User user) {
        ContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        Long activityId = work.getActivityId();
        var vote = abstractVoteRepository.findByActivityIdAndUserId(activityId, user.getId())
                .orElseThrow(() -> new RuntimeException("你还没有投过抽象票"));

        if (!vote.getWork().getId().equals(workId)) {
            throw new RuntimeException("你的抽象票投给了其他作品");
        }

        // 删除投票记录
        abstractVoteRepository.delete(vote);

        log.info("用户 {} 撤回了对作品 {} 的抽象票", user.getNickname(), work.getTitle());
    }

    /**
     * 查询用户在指定活动的投稿
     */
    public ContestWork getUserWork(Long activityId, Long userId) {
        return workRepository.findByActivityIdAndUserId(activityId, userId).orElse(null);
    }

    /**
     * 查询用户在指定活动的抽象票投给了哪个作品
     */
    public Long getAbstractVotedWorkId(Long activityId, Long userId) {
        return abstractVoteRepository.findByActivityIdAndUserId(activityId, userId)
                .map(v -> v.getWork().getId())
                .orElse(null);
    }

    /**
     * 删除用户自己的作品（及关联投票记录）
     */
    @Transactional
    public boolean deleteOwnWork(Long activityId, Long userId) {
        ContestPhase phase = getCurrentPhase(activityId);
        if (phase == ContestPhase.JUDGING || phase == ContestPhase.PRE_VOTE
                || phase == ContestPhase.VOTING || phase == ContestPhase.RESULTS) {
            throw new RuntimeException("评委打分已开始，无法删除作品");
        }

        ContestWork work = workRepository.findByActivityIdAndUserId(activityId, userId)
                .orElseThrow(() -> new RuntimeException("你还没有投稿作品"));

        boolean wasApproved = (work.getStatus() == ContestWork.WorkStatus.APPROVED);

        voteRepository.deleteByWorkId(work.getId());
        workRepository.delete(work);
        log.info("用户 {} 删除了作品 {}", userId, work.getTitle());

        return wasApproved;
    }

    /**
     * 根据票数排名计算网络投票得分
     */
    public int calculateVoteScore(int rank) {
        if (rank <= 0) {
            return 0;
        } else if (rank <= 5) {
            return 32 - rank * 2;
        } else if (rank <= 10) {
            return 20;
        } else if (rank <= 20) {
            return 18;
        } else if (rank <= 30) {
            return 16;
        } else {
            return 15;
        }
    }

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * 解析活动配置 JSON 为 Map
     */
    public Map<String, String> getConfigMap(Long activityId) {
        return configRepository.findByActivityId(activityId)
                .map(cfg -> parseJson(cfg.getConfigJson()))
                .orElse(Collections.emptyMap());
    }

    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return map;
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        s = s.trim();

        int i = 0;
        while (i < s.length()) {
            int k1 = s.indexOf('"', i);
            if (k1 < 0) break;
            int k2 = s.indexOf('"', k1 + 1);
            if (k2 < 0) break;
            String key = s.substring(k1 + 1, k2);

            int colon = s.indexOf(':', k2 + 1);
            if (colon < 0) break;
            int vi = colon + 1;
            while (vi < s.length() && s.charAt(vi) == ' ') vi++;
            if (vi >= s.length()) break;

            char ch = s.charAt(vi);
            String value;
            int next;

            if (ch == '"') {
                int end = vi + 1;
                while (end < s.length()) {
                    if (s.charAt(end) == '"' && s.charAt(end - 1) != '\\') break;
                    end++;
                }
                value = s.substring(vi + 1, end);
                next = end + 1;
            } else if (ch == '[' || ch == '{') {
                char open = ch, close = ch == '[' ? ']' : '}';
                int depth = 1;
                int end = vi + 1;
                boolean inStr = false;
                while (end < s.length() && depth > 0) {
                    char c = s.charAt(end);
                    if (c == '"' && (end == 0 || s.charAt(end - 1) != '\\')) inStr = !inStr;
                    if (!inStr) {
                        if (c == open) depth++;
                        else if (c == close) depth--;
                    }
                    if (depth > 0) end++;
                }
                value = s.substring(vi, end + 1);
                next = end + 1;
            } else {
                int end = vi;
                while (end < s.length() && s.charAt(end) != ',' && s.charAt(end) != '}') end++;
                value = s.substring(vi, end).trim();
                next = end;
            }

            map.put(key, value);

            while (next < s.length() && (s.charAt(next) == ',' || s.charAt(next) == ' ')) next++;
            i = next;
        }
        return map;
    }

    public static String mapToJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            String v = entry.getValue();
            if (v != null && (v.startsWith("[") || v.startsWith("{"))) {
                sb.append(v);
            } else {
                sb.append("\"").append(v != null ? v : "").append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    public static String extractJsonValue(String json, String targetKey) {
        if (json == null) return null;
        String search = "\"" + targetKey + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        char ch = json.charAt(start);
        if (ch == '"') {
            int end = start + 1;
            while (end < json.length() && json.charAt(end) != '"') end++;
            return json.substring(start + 1, end);
        } else if (ch == '[' || ch == '{') {
            char open = ch, close = ch == '[' ? ']' : '}';
            int depth = 1, end = start + 1;
            boolean inStr = false;
            while (end < json.length() && depth > 0) {
                char c = json.charAt(end);
                if (c == '"' && (end == 0 || json.charAt(end - 1) != '\\')) inStr = !inStr;
                if (!inStr) {
                    if (c == open) depth++;
                    else if (c == close) depth--;
                }
                if (depth > 0) end++;
            }
            return json.substring(start, end + 1);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(start, end).trim();
        }
    }

    private LocalDateTime parseDateTime(Map<String, String> configMap, String key) {
        String val = configMap.get(key);
        if (val == null || val.isEmpty()) return null;
        try {
            return LocalDateTime.parse(val, DT_FORMAT);
        } catch (Exception e) {
            log.warn("日期解析失败: key={}, value={}", key, val);
            return null;
        }
    }

    // ==================== 阶段控制 ====================

    public ContestPhase getCurrentPhase(Long activityId) {
        Map<String, String> cfg = getConfigMap(activityId);
        if (cfg.isEmpty()) return ContestPhase.BEFORE_START;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime submitStart = parseDateTime(cfg, "submitStart");
        LocalDateTime submitEnd = parseDateTime(cfg, "submitEnd");
        LocalDateTime judgeStart = parseDateTime(cfg, "judgeStart");
        LocalDateTime judgeEnd = parseDateTime(cfg, "judgeEnd");
        LocalDateTime voteStart = parseDateTime(cfg, "voteStart");
        LocalDateTime voteEnd = parseDateTime(cfg, "voteEnd");

        if (submitStart == null || now.isBefore(submitStart)) return ContestPhase.BEFORE_START;
        if (submitEnd != null && now.isBefore(submitEnd)) return ContestPhase.SUBMISSION;
        if (judgeStart != null && now.isBefore(judgeStart)) return ContestPhase.REVIEW;
        if (judgeEnd != null && now.isBefore(judgeEnd)) return ContestPhase.JUDGING;
        if (voteStart != null && now.isBefore(voteStart)) return ContestPhase.PRE_VOTE;
        if (voteEnd != null && now.isBefore(voteEnd)) return ContestPhase.VOTING;
        return ContestPhase.RESULTS;
    }

    public boolean shouldShowJudgeScore(Long activityId) {
        return getCurrentPhase(activityId) == ContestPhase.RESULTS;
    }

    public boolean shouldShowVoteCount(Long activityId) {
        ContestPhase phase = getCurrentPhase(activityId);
        return phase == ContestPhase.VOTING || phase == ContestPhase.RESULTS;
    }

    private String getPhaseRestrictionMessage(ContestPhase phase, String action) {
        return switch (phase) {
            case BEFORE_START -> "活动尚未开始，暂不可" + action;
            case SUBMISSION -> "投稿".equals(action) ? "投稿已截止" : "投票尚未开始";
            case REVIEW -> "投稿已截止，评委审核中";
            case JUDGING -> "评委打分中，暂不可" + action;
            case PRE_VOTE -> "评委打分已结束，投票尚未开始";
            case VOTING -> "投票".equals(action) ? "投票进行中" : "投稿已截止";
            case RESULTS -> "活动已结束";
        };
    }

    // ==================== 管理员审核功能 ====================

    public Page<ContestWork> getWorksForReview(Long activityId, String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (status != null && !status.isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            ContestWork.WorkStatus workStatus = ContestWork.WorkStatus.valueOf(status.toUpperCase());
            return workRepository.findByActivityIdAndStatusOrderByCreatedAtDesc(activityId, workStatus, pageRequest);
        }
        return workRepository.findByActivityIdOrderByCreatedAtDesc(activityId, pageRequest);
    }

    @Transactional
    public void reviewWork(Long workId, ContestWork.WorkStatus newStatus) {
        ContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));
        work.setStatus(newStatus);
        workRepository.save(work);
        log.info("作品 {} 审核结果: {}", workId, newStatus);
    }

    // ==================== 裁判评分功能 ====================

    public boolean isJudge(Long activityId, Long userId) {
        return judgeRepository.existsByActivityIdAndUserId(activityId, userId);
    }

    @Transactional
    public void submitJudgeScore(Long workId, User judge, double score) {
        ContestWork work = workRepository.findById(workId)
                .orElseThrow(() -> new RuntimeException("作品不存在"));

        if (!isJudge(work.getActivityId(), judge.getId())) {
            throw new RuntimeException("您不是本次活动的裁判");
        }

        ContestPhase phase = getCurrentPhase(work.getActivityId());
        if (phase != ContestPhase.JUDGING) {
            throw new RuntimeException("当前不是评委打分阶段");
        }

        if (judgeScoreRepository.existsByWorkIdAndJudgeId(workId, judge.getId())) {
            throw new RuntimeException("您已对该作品评分，不可修改");
        }

        if (score < 0 || score > 10) {
            throw new RuntimeException("分数必须在 0 ~ 10 之间");
        }

        double roundedScore = Math.round(score * 10.0) / 10.0;

        ContestJudgeScore judgeScore = ContestJudgeScore.builder()
                .work(work)
                .judge(judge)
                .score(roundedScore)
                .build();
        judgeScoreRepository.save(judgeScore);

        log.info("裁判 {} 为作品 {} 评分: {}", judge.getNickname(), work.getTitle(), roundedScore);
    }

    public Double getJudgeScoreForWork(Long workId, Long judgeId) {
        return judgeScoreRepository.findByWorkIdAndJudgeId(workId, judgeId)
                .map(ContestJudgeScore::getScore)
                .orElse(null);
    }

    public int[] getJudgeProgress(Long activityId, Long judgeId) {
        List<ContestWork> approvedWorks = getApprovedWorks(activityId);
        int total = approvedWorks.size();
        List<ContestJudgeScore> scored = judgeScoreRepository.findByJudgeIdAndWorkActivityId(judgeId, activityId);
        int scoredCount = scored.size();
        return new int[]{scoredCount, total};
    }

    // ==================== 实时聚合查询 ====================

    public static int getPopularityPoint(int rank) {
        if (rank == 1) return 30;
        if (rank == 2) return 28;
        if (rank == 3) return 26;
        if (rank == 4) return 24;
        if (rank == 5) return 22;
        if (rank <= 10) return 20;
        if (rank <= 20) return 18;
        if (rank <= 30) return 16;
        return 15;
    }

    /** 获取某作品的投票数 */
    public int getVoteCount(Long workId) {
        return (int) voteRepository.countByWorkId(workId);
    }

    /** 获取某作品的抽象票数 */
    public int getAbstractVoteCount(Long workId) {
        return (int) abstractVoteRepository.countByWorkId(workId);
    }

    /** 获取某作品的裁判平均分 */
    public Double getJudgeAvgScore(Long workId) {
        return judgeScoreRepository.avgScoreByWorkId(workId);
    }

    /** 批量获取指定活动所有作品的投票数 */
    public Map<Long, Integer> getVoteCountMap(List<ContestWork> works) {
        Map<Long, Integer> map = new HashMap<>();
        for (ContestWork w : works) {
            map.put(w.getId(), (int) voteRepository.countByWorkId(w.getId()));
        }
        return map;
    }

    /** 批量获取指定活动所有作品的抽象票数 */
    public Map<Long, Integer> getAbstractVoteCountMap(List<ContestWork> works) {
        Map<Long, Integer> map = new HashMap<>();
        for (ContestWork w : works) {
            map.put(w.getId(), (int) abstractVoteRepository.countByWorkId(w.getId()));
        }
        return map;
    }

    /** 批量获取指定活动所有作品的裁判平均分 */
    public Map<Long, Double> getJudgeScoreMap(List<ContestWork> works) {
        Map<Long, Double> map = new HashMap<>();
        for (ContestWork w : works) {
            map.put(w.getId(), judgeScoreRepository.avgScoreByWorkId(w.getId()));
        }
        return map;
    }

    /** 根据票数排名计算人气得分映射 */
    public Map<Long, Integer> getPopularityScoreMap(Long activityId) {
        List<ContestWork> works = getApprovedWorks(activityId);
        Map<Long, Integer> voteCounts = getVoteCountMap(works);

        List<ContestWork> sortedByVotes = works.stream()
                .sorted(Comparator.comparingInt((ContestWork w) ->
                        voteCounts.getOrDefault(w.getId(), 0)).reversed())
                .toList();

        Map<Long, Integer> map = new HashMap<>();
        for (int i = 0; i < sortedByVotes.size(); i++) {
            map.put(sortedByVotes.get(i).getId(), getPopularityPoint(i + 1));
        }
        return map;
    }

    /** 实时计算指定活动所有作品的最终得分（裁判分×7 + 人气分） */
    public Map<Long, Double> computeFinalScores(Long activityId) {
        List<ContestWork> works = getApprovedWorks(activityId);
        Map<Long, Double> judgeScores = getJudgeScoreMap(works);
        Map<Long, Integer> popMap = getPopularityScoreMap(activityId);

        Map<Long, Double> result = new HashMap<>();
        for (ContestWork work : works) {
            Double js = judgeScores.get(work.getId());
            double judgePart = js != null ? Math.round(js * 10.0) / 10.0 * 7 : 0.0;
            int popPart = popMap.getOrDefault(work.getId(), 15);
            result.put(work.getId(), Math.round((judgePart + popPart) * 10.0) / 10.0);
        }

        log.info("已计算活动 {} 的 {} 个作品最终得分", activityId, works.size());
        return result;
    }
}
