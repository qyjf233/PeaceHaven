package com.potato.peacehaven.ai.retrieval;

/**
 * 记忆 RAG 服务接口
 * <p>
 * 从用户长期记忆（BotUserMemory）中检索与当前消息相关的事实，
 * 与 Style RAG（ChatHistoryRetrievalService）分离，职责单一。
 * </p>
 */
public interface MemoryRagService {

    /**
     * 检索与当前消息相关的用户记忆
     *
     * @param senderWxid     发送者 wxid
     * @param currentMessage 当前消息内容
     * @return 格式化的记忆文本（如"关于用户：喜欢LOL，在上海，养猫"），无匹配时返回空字符串
     */
    String retrieveRelevantMemory(String senderWxid, String currentMessage);
}
