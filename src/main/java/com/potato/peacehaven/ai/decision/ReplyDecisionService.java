package com.potato.peacehaven.ai.decision;

import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 回复决策服务
 * <p>
 * 根据多种条件判断是否应该回复群消息，避免每条都回复。
 * 决策优先级：@提及 > only-at配置 > 提问检测 > cooldown > 随机概率 > 每日上限
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyDecisionService {

    private final AiProperties aiProps;

    /** 今日已回复次数 */
    private final AtomicInteger dailyCount = new AtomicInteger(0);

    /** 记录当前日期，用于每日重置 */
    private final AtomicReference<LocalDate> currentDay = new AtomicReference<>(LocalDate.now());

    /** 上次回复时间戳（毫秒），key=chatroomId */
    private final Map<String, Long> lastReplyTime = new ConcurrentHashMap<>();

    /** 提问检测正则（中英文问号 + 常见疑问词） */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "[?？]|怎么|为什么|如何|什么|哪[里个]|是不是|能不能|可以吗|有没有|吗$|呢$|吧[?？]?"
    );

    /**
     * 决策是否回复
     *
     * @param chatroomId  群聊 ID
     * @param senderWxid  发送者 wxid
     * @param content     消息内容
     * @param isMentioned 是否被 @提及
     * @return 决策结果
     */
    public ReplyDecision decide(String chatroomId, String senderWxid, String content, boolean isMentioned) {
        if (!aiProps.isReady()) {
            return ReplyDecision.skip("AI 系统未就绪");
        }

        AiProperties.ReplyConfig cfg = aiProps.getReply();
        resetDailyIfNeeded();

        // 1. 被 @提及 -> 必须回复
        if (isMentioned) {
            return ReplyDecision.reply("被@提及");
        }

        // 2. only-at 模式 -> 仅@时才回复
        if (cfg.isOnlyAt()) {
            return ReplyDecision.skip("only-at 模式，未被@不回复");
        }

        // 3. 每日上限检查
        if (dailyCount.get() >= cfg.getMaxPerDay()) {
            return ReplyDecision.skip("已达每日上限 " + cfg.getMaxPerDay());
        }

        // 4. cooldown 检查
        Long lastTime = lastReplyTime.get(chatroomId);
        long now = System.currentTimeMillis();
        long cooldownMs = cfg.getCooldownSeconds() * 1000L;
        if (lastTime != null && (now - lastTime) < cooldownMs) {
            return ReplyDecision.skip("冷却中（距上次 " + (now - lastTime) / 1000 + "s）");
        }

        // 5. 提问检测 -> 高概率回复（70%）
        boolean isQuestion = content != null && QUESTION_PATTERN.matcher(content).find();
        if (isQuestion) {
            if (ThreadLocalRandom.current().nextDouble() < 0.7) {
                return ReplyDecision.reply("检测到提问");
            }
            return ReplyDecision.skip("检测到提问但概率未命中");
        }

        // 6. 随机概率
        if (ThreadLocalRandom.current().nextDouble() < cfg.getRandomRate()) {
            return ReplyDecision.reply("随机触发");
        }

        return ReplyDecision.skip("无触发条件");
    }

    /**
     * 记录一次回复（Pipeline 发送成功后调用）
     */
    public void recordReply(String chatroomId) {
        resetDailyIfNeeded();
        dailyCount.incrementAndGet();
        lastReplyTime.put(chatroomId, System.currentTimeMillis());
        log.debug("[Decision] 记录回复 chatroom={}，今日第 {} 次", chatroomId, dailyCount.get());
    }

    /**
     * 获取今日已回复次数
     */
    public int getDailyCount() {
        resetDailyIfNeeded();
        return dailyCount.get();
    }

    /**
     * 跨日重置计数器
     */
    private void resetDailyIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay.get())) {
            currentDay.set(today);
            dailyCount.set(0);
            lastReplyTime.clear();
            log.info("[Decision] 新的一天，回复计数器已重置");
        }
    }
}
