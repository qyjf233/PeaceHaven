package com.potato.peacehaven.ai.topic;

/**
 * Bot 回复行为分类
 * <p>
 * 用于对话推进器追踪 bot 的"行为模式"而非"文字相似度"。
 * 例如："不骂"、"骂你干啥"、"不会"、"算了"、"不了" 文字不同但行为都是 REFUSE。
 * </p>
 */
public enum BotBehavior {

    /** 拒绝对方请求（不骂、不要、算了、不了） */
    REFUSE,

    /** 接受/配合对方请求（行、好吧、给你） */
    ACCEPT,

    /** 调侃/开玩笑（反向调侃、戏弄、玩梗） */
    JOKE,

    /** 反问/反客为主（你先示范、你怎么这么执着） */
    COUNTER,

    /** 主动转移话题（换个话题、说起别的） */
    CHANGE_TOPIC,

    /** 结束当前话题（行吧、到此为止、可以了） */
    END,

    /** 普通对话/无明显行为倾向（日常回复、信息交流） */
    NEUTRAL,

    /** 无法判断 */
    UNKNOWN;

    /**
     * 判断两个行为是否"同类"（用于连续行为检测）
     * <p>
     * REFUSE + REFUSE = 同类
     * REFUSE + ACCEPT = 不同类
     * NEUTRAL 不参与同类判定（视为无倾向）
     * </p>
     */
    public boolean isSameCategory(BotBehavior other) {
        if (this == UNKNOWN || other == UNKNOWN) return false;
        if (this == NEUTRAL || other == NEUTRAL) return false;
        return this == other;
    }
}
