package com.potato.peacehaven.ai.topic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则的话题提取器
 * <p>
 * 策略：
 * 1. 过滤纯语气/表情消息（"哈哈"/"6"/"牛"/"笑死"等）返回 null
 * 2. 优先提取疑问句中的核心名词（"去哪打球" -> "打球"）
 * 3. 提取消息中的中文名词词组（2-4字）
 * 4. 无法提取时返回 null
 * </p>
 */
@Slf4j
@Component
public class SimpleTopicExtractor implements TopicExtractor {

    /** 纯语气/表情/无意义消息，不提取话题 */
    private static final Set<String> NOISE_WORDS = Set.of(
            "哈哈", "哈哈哈", "哈哈哈哈", "呵呵", "嗯", "嗯嗯", "哦", "哦哦",
            "好的", "ok", "OK", "好", "行", "可以", "收到", "明白", "了解",
            "6", "666", "6666", "牛", "牛逼", "厉害了", "笑死", "绝了",
            "确实", "是的", "不是", "没有", "有", "对", "对的", "啊",
            "嗯嗯嗯", "哈哈哈哈哈", "xswl", "yyds", "awsl", "绷不住了",
            "真绷不住", "真的假的", "好吧", "行吧", "得了吧", "算了吧",
            "我去", "卧槽", "woc", "nb", "GG", "gg"
    );

    /** 疑问词（用于判断是否是提问） */
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "怎么|为什么|如何|什么|哪[里个]|是不是|能不能|可以吗|有没有|[?？]"
    );

    /** 中文名词词组提取（2-4字连续中文） */
    private static final Pattern CHINESE_NOUN_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,4}"
    );

    /** 停用词（功能词，不代表话题） */
    private static final Set<String> STOP_WORDS = Set.of(
            "的是", "不是", "可以", "就是", "还是", "或者", "这个", "那个", "什么",
            "怎么", "没有", "一个", "我们", "你们", "他们", "自己", "现在",
            "然后", "因为", "所以", "如果", "但是", "而且", "已经", "知道",
            "觉得", "真的", "其实", "应该", "不要", "好的", "哈哈哈", "呵呵",
            "有没有", "是不是", "能不能", "可不可以", "为什么", "怎么办",
            "不是吧", "真的吗", "好吧", "行吧", "得了", "算了", "没事",
            "谢谢", "感谢", "辛苦", "客气", "请问", "问题", "情况"
    );

    /** @提及 正则（微信格式：@名字 + 特殊空白符 U+2005 或普通空格） */
    private static final Pattern AT_MENTION_PATTERN = Pattern.compile(
            "@[\\w\\u4e00-\\u9fa5]+[\\u2005\\u200B\\s]*"
    );

    @Override
    public String extract(String content) {
        if (content == null || content.isBlank()) return null;

        // 0. 去除 @提及（@薯条君 等不算话题）
        String stripped = AT_MENTION_PATTERN.matcher(content).replaceAll("").trim();
        if (stripped.isBlank()) return null;

        String trimmed = stripped;

        // 1. 纯语气消息过滤
        String normalized = trimmed.toLowerCase().replaceAll("[\\s!！。.]+", "");
        if (NOISE_WORDS.contains(normalized)) {
            log.debug("[TopicExtractor] 噪音消息跳过: {}", trimmed);
            return null;
        }

        // 2. 太短的消息（纯单字）不提取
        if (trimmed.length() < 2) return null;

        // 3. 提取中文名词词组，选取最有代表性的一个
        String bestTopic = selectBestNoun(trimmed);
        if (bestTopic != null) {
            log.debug("[TopicExtractor] 提取话题: '{}' <- '{}'", bestTopic, 
                    trimmed.length() > 30 ? trimmed.substring(0, 30) + "..." : trimmed);
        }
        return bestTopic;
    }

    /**
     * 从消息中选取最具话题性的中文名词词组
     * <p>
     * 策略：提取所有 2-4 字中文词组，排除停用词，
     * 优先选择 3-4 字的（更像实体名词），同等长度取第一个出现的。
     * </p>
     */
    private String selectBestNoun(String content) {
        // 去除标点
        String clean = content.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ");

        Matcher matcher = CHINESE_NOUN_PATTERN.matcher(clean);
        String fallback = null;

        while (matcher.find()) {
            String word = matcher.group();
            if (STOP_WORDS.contains(word)) continue;

            // 3-4 字优先（更像实体名词）
            if (word.length() >= 3) return word;
            if (fallback == null) fallback = word;
        }

        return fallback;
    }
}
