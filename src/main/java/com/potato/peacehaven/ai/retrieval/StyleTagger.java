package com.potato.peacehaven.ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.Set;

/**
 * 风格标签分类器（纯规则，零 LLM 调用）
 * <p>
 * 对每条聊天历史记录进行风格分类，存入 VectorStore metadata：
 * <ul>
 *   <li>common — 普通表达，占大多数</li>
 *   <li>catchphrase — 包含口头禅/特色词</li>
 *   <li>humor — 包含幽默/调侃元素</li>
 *   <li>rare — 强招牌句式（已读乱回、荒诞反问等极低频表达）</li>
 * </ul>
 * </p>
 * <p>
 * 用途：Style RAG 检索时实现多样性平衡，防止特色表达霸占结果，
 * 导致模型误以为"这个人就是这样讲话的"。
 * </p>
 */
@Slf4j
@Component
public class StyleTagger {

    /** 口头禅 / 特色词模式 */
    private static final Pattern CATCHPHRASE_PATTERN = Pattern.compile(
            "牛福|哦坤|不是哥们|头大了|神了|唐完了|沟槽的"
    );

    /** 强招牌句式（极低频，<5%） */
    private static final Pattern RARE_PATTERN = Pattern.compile(
            "哦坤啊感谢|敢不敢.{2,}"
    );

    /** 幽默 / 调侃模式 */
    private static final Pattern HUMOR_PATTERN = Pattern.compile(
            "哈{3,}|笑死|绷不住了|离谱|6{3,}|绷|乐"
    );

    /**
     * 对消息内容打风格标签
     *
     * @param content 消息文本
     * @return 风格类型：common / catchphrase / humor / rare
     */
    public String tag(String content) {
        if (content == null || content.isBlank()) {
            return "common";
        }

        // 优先级：rare > catchphrase > humor > common
        if (RARE_PATTERN.matcher(content).find()) {
            return "rare";
        }
        if (CATCHPHRASE_PATTERN.matcher(content).find()) {
            return "catchphrase";
        }
        if (HUMOR_PATTERN.matcher(content).find()) {
            return "humor";
        }
        return "common";
    }

    /**
     * 判断是否为"特色表达"（非 common）
     */
    public boolean isFeatured(String styleType) {
        return styleType != null && !"common".equals(styleType);
    }

    // ========================================================================
    //  analyze() — 单条消息多维特征分析（v4.1 新增）
    // ========================================================================

    /** 正式书面语模式 */
    private static final Pattern FORMAL_PATTERN = Pattern.compile(
            "确实|因此|然而|此外|综上所述|根据|关于|对于|由于|以便|进一步"
    );

    /** 俚语/网络用语 */
    private static final Pattern SLANG_PATTERN = Pattern.compile(
            "牛福|哦坤|不是哥们|头大了|神了|唐完了|沟槽的|6{3,}|绷|乐|笑死|离谱|绝了"
    );

    /** Emoji 字符范围（常用 Emoji Unicode 区间） */
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F600}-\\x{1F64F}" +  // Emoticons
            "\\x{1F300}-\\x{1F5FF}" +    // Symbols & Pictographs
            "\\x{1F680}-\\x{1F6FF}" +    // Transport & Map
            "\\x{1F1E0}-\\x{1F1FF}" +    // Flags
            "\\x{2702}-\\x{27B0}" +      // Dingbats
            "\\x{FE00}-\\x{FE0F}" +      // Variation Selectors
            "\\x{1F900}-\\x{1F9FF}]"     // Supplemental Symbols
    );

    /** 标点符号 */
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile(
            "[，。！？；：、……——～·.,!?;:~]"
    );

    /** 温暖/共情表达 */
    private static final Pattern WARMTH_PATTERN = Pattern.compile(
            "加油|辛苦了|早点休息|注意身体|没事的|没关系|辛苦了|别太累|照顾好自己|心疼|抱抱"
    );

    /** 直接/不客套表达 */
    private static final Pattern DIRECT_PATTERN = Pattern.compile(
            "不|没|别|滚|走|得了|行了|算了|随便|不想|不要|不去|不干"
    );

    private static final Set<String> DIRECT_SHORT = Set.of(
            "不", "没", "行", "滚", "嗯", "哦", "啊", "噢"
    );

    /**
     * 对单条消息做多维风格特征分析
     * <p>
     * 输出 StyleFeature，包含 Core Persona 维度 + Expression Mode + 社交上下文。
     * 不含 variance（variance 由 {@link StyleAggregator} 聚合产出）。
     * </p>
     *
     * @param content 消息文本
     * @return 风格特征
     */
    public StyleFeature analyze(String content) {
        if (content == null || content.isBlank()) {
            return StyleFeature.builder().category("common").length(0).build();
        }

        String text = content.trim();
        int len = text.length();

        // --- Core Persona 维度 ---
        double humor = HUMOR_PATTERN.matcher(text).find() ? clamp(0.6 + len * 0.005, 1.0) : 0.05;
        double sarcasm = 0.05;
        if (CATCHPHRASE_PATTERN.matcher(text).find()) sarcasm = clamp(sarcasm + 0.4, 1.0);
        if (HUMOR_PATTERN.matcher(text).find() && sarcasm > 0.3) sarcasm = clamp(sarcasm + 0.2, 1.0);

        double warmth = WARMTH_PATTERN.matcher(text).find() ? clamp(0.5 + len * 0.003, 1.0) : 0.05;

        double directness = 0.3; // baseline
        if (DIRECT_PATTERN.matcher(text).find()) directness = clamp(directness + 0.3, 1.0);
        if (len <= 5 && DIRECT_SHORT.contains(text)) directness = clamp(directness + 0.2, 1.0);
        // 客套话降低直接度
        if (text.contains("谢谢") || text.contains("感谢") || text.contains("请问")) {
            directness *= 0.5;
        }

        // --- Expression Mode ---
        double formal = FORMAL_PATTERN.matcher(text).find() ? clamp(0.5 + len * 0.005, 1.0) : clamp(0.05 + (len > 100 ? 0.15 : 0), 1.0);
        double slang = SLANG_PATTERN.matcher(text).find() ? clamp(0.5 + len * 0.003, 1.0) : 0.05;

        long emojiCount = EMOJI_PATTERN.matcher(text).results().count();
        double emoji = len > 0 ? clamp((double) emojiCount / len * 10, 1.0) : 0.0;

        long punctCount = PUNCTUATION_PATTERN.matcher(text).results().count();
        double punct = len > 0 ? clamp((double) punctCount / len, 1.0) : 0.0;

        // --- 社交上下文维度（规则推断）---
        double intimacyHumor = 0.0;
        if (sarcasm > 0.3 && warmth > 0.3) intimacyHumor = clamp(sarcasm * warmth * 2, 1.0);

        double empathyHidden = 0.0;
        if (sarcasm > 0.2 && warmth > 0.2) empathyHidden = clamp((sarcasm + warmth) / 2, 1.0);

        double teasingAllowed = 0.0;
        if (isFeatured(tag(text)) && humor > 0.3) teasingAllowed = clamp(humor + 0.2, 1.0);

        // --- 兼容旧标签 ---
        String category = tag(text);

        return StyleFeature.builder()
                .humorScore(humor)
                .sarcasmScore(sarcasm)
                .warmthScore(warmth)
                .directnessScore(directness)
                .formalScore(formal)
                .slangScore(slang)
                .length(len)
                .emojiUsage(emoji)
                .punctuation(punct)
                .intimacyHumor(intimacyHumor)
                .empathyHidden(empathyHidden)
                .teasingAllowed(teasingAllowed)
                .category(category)
                .build();
    }

    private static double clamp(double value, double max) {
        return Math.min(Math.max(value, 0.0), max);
    }
}
