package com.potato.peacehaven.ai.topic;

/**
 * 话题判断服务接口
 * <p>
 * 判断当前消息是否需要执行 RAG 检索。
 * 短消息、纯表情、语气词等无需检索，节省 Embedding 调用开销。
 * </p>
 */
public interface TopicJudgeService {

    /**
     * 判断消息是否需要 RAG 检索
     *
     * @param content    消息文本
     * @param chatroomId 群聊 ID（可为 null，表示私聊）
     * @return true=需要检索 RAG，false=跳过
     */
    boolean needsRagLookup(String content, String chatroomId);
}
