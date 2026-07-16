package com.potato.peacehaven.ai.topic;

/**
 * 话题提取接口
 * <p>
 * 从消息内容中提取话题关键词（2-4字），用于对话状态追踪和话题锚定检测。
 * 实现可以是规则引擎、LLM 提取或混合策略。
 * </p>
 */
public interface TopicExtractor {

    /**
     * 从消息内容中提取话题
     *
     * @param content 消息文本
     * @return 话题关键词（2-4字），无法提取时返回 null
     */
    String extract(String content);
}
