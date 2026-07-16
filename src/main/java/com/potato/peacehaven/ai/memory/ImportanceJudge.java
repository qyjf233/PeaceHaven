package com.potato.peacehaven.ai.memory;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 记忆重要性评分器
 * <p>
 * 纯规则评分（零 LLM 调用），判断一条记忆候选是否值得存储：
 * <ul>
 *   <li>噪音消息 → 丢弃</li>
 *   <li>临时状态 → 低分 + 短 TTL</li>
 *   <li>事实/经历 → 中分 + 半年 TTL</li>
 *   <li>身份/价值观 → 高分 + 永久</li>
 *   <li>关系 → 中高分 + 永久</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ImportanceJudge {

    // ===== 噪音词精确匹配 → 0.0 =====
    private static final Set<String> NOISE_EXACT = Set.of(
            "哈哈", "哈哈哈", "哈哈哈哈", "666", "牛", "牛逼", "好的", "嗯", "哦",
            "ok", "嗯嗯", "行", "可以", "没事", "没事没事", "谢谢", "谢了",
            "嗯嗯嗯", "哈哈哈哈哈", "好嘞", "好滴", "好哒", "收到", "了解",
            "哈哈确实", "确实", "是吧", "对", "对的", "是的", "没错", "好吧",
            "啊这", "emmm", "emm", "awsl", "yyds", "xswl"
    );

    // ===== 身份/价值观模式 → 0.8-1.0 =====
    private static final Pattern IDENTITY_PATTERN = Pattern.compile(
            "我是|我是做|我从事|我的职业|我的专业|" +
            "我不[想喜欢做当要]|我决定|我打算|" +
            "我相信|我认为|我觉得.*很重要|" +
            "素食|吃素|信仰|宗教|" +
            "我毕业[于从]|我的大学|我[在读读].*[大学硕士博士]"
    );

    // ===== 关系模式 → 0.6-0.8 =====
    private static final Pattern RELATIONSHIP_PATTERN = Pattern.compile(
            "我[的]?(老婆|丈夫|男朋友|女朋友|对象)|我[的]?(爸|妈|父亲|母亲|哥|姐|弟|妹)|" +
            "[是是我].*[室友同学同事朋友]|" +
            "我[们]?[结离][婚了]|我[有没][个]?(儿子|女儿|孩子)|" +
            "[我他她].*住在|[我他她].*在.*[工作上班]"
    );

    // ===== 事实/经历模式 → 0.5-0.7 =====
    private static final Pattern FACT_PATTERN = Pattern.compile(
            "我[去来过到买养种学]|我[有没][一]?[只条辆台套房]|" +
            "我[住搬]在|我[的]?[猫狗车房]|" +
            "我去[了过]|我[吃吃喝试试]|" +
            "我[在玩在追在看在听]|我[的]?[生日星座]"
    );

    // ===== 临时状态模式 → 0.2-0.3 =====
    private static final Pattern STATE_PATTERN = Pattern.compile(
            "今天|最近|这几天|本周|这周|" +
            "好累|好忙|好困|好饿|好冷|好热|" +
            "有点[累忙困烦闷]|心情[好不好差]|" +
            "感冒|生病|加班|出差"
    );

    /**
     * 综合评分
     *
     * @param content 记忆内容
     * @param type    LLM 标注的类型（identity/preference/episode/relationship）
     * @return 评分结果
     */
    public ImportanceResult judge(String content, String type) {
        if (content == null || content.isBlank()) {
            return new ImportanceResult(0, 0, 0, false);
        }

        String trimmed = content.trim();

        // 1. 噪音词 → 丢弃
        if (NOISE_EXACT.contains(trimmed.toLowerCase()) || trimmed.length() <= 2) {
            return new ImportanceResult(0, 0, 0, false);
        }

        double importance;
        double confidence = 0.8; // 默认规则置信度
        int ttlDays;

        // 2. 按模式匹配评分（从高到低优先级）
        if (IDENTITY_PATTERN.matcher(trimmed).find() || "identity".equalsIgnoreCase(type)) {
            importance = 0.85;
            ttlDays = 0; // 永久
        } else if (RELATIONSHIP_PATTERN.matcher(trimmed).find() || "relationship".equalsIgnoreCase(type)) {
            importance = 0.7;
            ttlDays = 0; // 永久
        } else if (FACT_PATTERN.matcher(trimmed).find()) {
            importance = 0.6;
            ttlDays = 180; // 半年
        } else if (STATE_PATTERN.matcher(trimmed).find()) {
            importance = 0.25;
            ttlDays = 30; // 月度
            confidence = 0.6;
        } else {
            // 未命中任何模式：基于长度和 type 给一个中间分
            if ("preference".equalsIgnoreCase(type)) {
                importance = 0.55;
                ttlDays = 180;
            } else if ("episode".equalsIgnoreCase(type)) {
                importance = 0.5;
                ttlDays = 180;
            } else {
                // 通用：短消息低分，长消息稍高分
                importance = trimmed.length() > 10 ? 0.45 : 0.3;
                ttlDays = trimmed.length() > 10 ? 90 : 30;
            }
        }

        boolean shouldStore = importance >= 0.3;

        log.debug("[ImportanceJudge] content='{}' type={} → importance={}, confidence={}, ttl={}, store={}",
                trimmed.length() > 30 ? trimmed.substring(0, 30) + "..." : trimmed,
                type, String.format("%.2f", importance), String.format("%.2f", confidence),
                ttlDays == 0 ? "permanent" : ttlDays + "d", shouldStore);

        return new ImportanceResult(importance, confidence, ttlDays, shouldStore);
    }

    /**
     * 评分结果
     *
     * @param importance  重要性（0-1）
     * @param confidence  可信度（0-1）
     * @param ttlDays     存活天数（0=永久）
     * @param shouldStore 是否应该存储
     */
    @Getter
    @AllArgsConstructor
    public static class ImportanceResult {
        private final double importance;
        private final double confidence;
        private final int ttlDays;
        private final boolean shouldStore;
    }
}
