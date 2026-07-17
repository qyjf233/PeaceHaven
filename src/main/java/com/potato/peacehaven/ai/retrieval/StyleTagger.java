package com.potato.peacehaven.ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 风格标签分类器（纯规则，零 LLM 调用）
 * <p>
 * 职责分为两层：
 * <ul>
 *   <li>{@link #tag(String)} — 对消息打风格标签（common/catchphrase/humor/rare），用于 RAG 多样性平衡</li>
 *   <li>{@link #analyze(String)} — 提取客观特征（长度/emoji/标点/正式词/俚语），用于统计聚合</li>
 * </ul>
 * 主观语义维度（humor/sarcasm/warmth/directness 等）已移除，改由 LLM Observation 生成。
 * </p>
 */
@Slf4j
@Component
public class StyleTagger {

    // ===== 标签分类用 Pattern（仅用于 tag()，不用于打分）=====

    /** 口头禅 / 特色词模式 */
    private static final Pattern CATCHPHRASE_PATTERN = Pattern.compile(
            "牛福|哦坤|不是哥们|头大了|神了|唐完了|沟槽的"
    );

    /** 强招牌句式（极低频，<5%） */
    private static final Pattern RARE_PATTERN = Pattern.compile(
            "哦坤啊感谢|敢不敢.{2,}"
    );

    /** 幽默 / 调侃模式（仅用于 tag 标签，不打分） */
    private static final Pattern HUMOR_PATTERN = Pattern.compile(
            "哈{3,}|笑死|绷不住了|离谱|6{3,}|绷|乐"
    );

    // ===== 客观统计用 Pattern =====

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

    /**
     * 对消息内容打风格标签（用于 RAG 多样性平衡）
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
    //  analyze() — 单条消息客观特征分析
    // ========================================================================

    /**
     * 对单条消息提取客观风格特征
     * <p>
     * 只输出可量化、跨模型一致的客观特征：
     * length / emoji / punctuation / formal / slang / category。
     * <br>
     * 主观语义维度（humor/sarcasm/warmth/directness）不在此处分析，
     * 改由 LLM Observation 从批量消息中生成。
     * </p>
     *
     * @param content 消息文本
     * @return 客观风格特征
     */
    public StyleFeature analyze(String content) {
        if (content == null || content.isBlank()) {
            return StyleFeature.builder().category("common").length(0).build();
        }

        String text = content.trim();
        int len = text.length();

        // --- 客观 Expression Mode ---
        double formal = FORMAL_PATTERN.matcher(text).find()
                ? clamp(0.5 + len * 0.005, 1.0)
                : clamp(0.05 + (len > 100 ? 0.15 : 0), 1.0);

        double slang = SLANG_PATTERN.matcher(text).find()
                ? clamp(0.5 + len * 0.003, 1.0)
                : 0.05;

        long emojiCount = EMOJI_PATTERN.matcher(text).results().count();
        double emoji = len > 0 ? clamp((double) emojiCount / len * 10, 1.0) : 0.0;

        long punctCount = PUNCTUATION_PATTERN.matcher(text).results().count();
        double punct = len > 0 ? clamp((double) punctCount / len, 1.0) : 0.0;

        // --- 风格标签（用于 RAG 多样性，不参与打分）---
        String category = tag(text);

        return StyleFeature.builder()
                .formalScore(formal)
                .slangScore(slang)
                .length(len)
                .emojiUsage(emoji)
                .punctuation(punct)
                .category(category)
                .build();
    }

    private static double clamp(double value, double max) {
        return Math.min(Math.max(value, 0.0), max);
    }
}
