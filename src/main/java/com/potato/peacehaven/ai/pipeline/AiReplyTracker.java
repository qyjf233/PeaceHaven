package com.potato.peacehaven.ai.pipeline;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 回复追踪器
 * <p>
 * 用于追踪 AI 发送的消息内容指纹，防止 AI 回复回流到 RAG 训练数据。
 * 当 Webhook 收到 is_self=true 的消息时，可通过 {@link #isAiReply(String)} 判断是否为 AI 生成。
 * </p>
 * <p>指纹有效期：60 秒（webhook 回调通常在几秒内到达）</p>
 */
@Component
public class AiReplyTracker {

    /** key=contentHash, value=注册时的毫秒时间戳 */
    private final ConcurrentHashMap<String, Long> fingerprints = new ConcurrentHashMap<>();

    /** 指纹有效期（毫秒） */
    private static final long TTL_MS = 60_000;

    /**
     * 注册一条 AI 发送的消息
     *
     * @param content 发送的文本内容
     */
    public void register(String content) {
        if (content == null || content.isBlank()) return;
        String hash = fingerprint(content);
        fingerprints.put(hash, System.currentTimeMillis());
        evictExpired();
    }

    /**
     * 检查给定内容是否是 AI 近期发送的回复
     *
     * @param content 消息内容
     * @return true=该内容是 AI 生成的回复
     */
    public boolean isAiReply(String content) {
        if (content == null || content.isBlank()) return false;
        String hash = fingerprint(content);
        Long timestamp = fingerprints.get(hash);
        if (timestamp == null) return false;

        if (System.currentTimeMillis() - timestamp > TTL_MS) {
            fingerprints.remove(hash);
            return false;
        }
        return true;
    }

    /**
     * 生成内容指纹（SHA-256 前 16 字符，足够区分短文本）
     */
    private String fingerprint(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 一定存在，不可能走到这里
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * 清理过期指纹，防止内存泄漏
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        fingerprints.entrySet().removeIf(entry -> now - entry.getValue() > TTL_MS);
    }
}
