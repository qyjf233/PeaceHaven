package com.potato.peacehaven.ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

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
}
