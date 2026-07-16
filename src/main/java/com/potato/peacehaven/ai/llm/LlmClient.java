package com.potato.peacehaven.ai.llm;

import java.util.List;

/**
 * LLM 客户端接口
 * <p>
 * 抽象大语言模型调用，支持多种供应商。
 * 当前实现：OpenAI 兼容格式（DeepSeek / 通义千问 / Moonshot 等）。
 * </p>
 */
public interface LlmClient {

    /**
     * 调用大模型聊天接口（同步模式）
     *
     * @param messages    消息列表（system/user/assistant）
     * @param temperature 温度参数（0-2），null 时使用默认值
     * @param maxTokens   最大输出 token 数，null 时使用默认值
     * @return 模型生成的回复文本，失败时返回 null
     */
    String chat(List<LlmMessage> messages, Double temperature, Integer maxTokens);

    /**
     * 使用默认参数调用
     */
    default String chat(List<LlmMessage> messages) {
        return chat(messages, null, null);
    }
}
