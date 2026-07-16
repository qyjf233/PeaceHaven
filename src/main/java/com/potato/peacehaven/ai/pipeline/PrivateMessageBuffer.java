package com.potato.peacehaven.ai.pipeline;

import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 私聊消息聚合器（Debounce Buffer）
 * <p>
 * 解决问题：用户习惯一句话拆成多条短消息发送，如果每条都回会显得机器人很"抢话"。
 * </p>
 * <p>
 * 工作机制：
 * <ol>
 *   <li>收到私聊消息时，追加到该发送者的缓冲区</li>
 *   <li>启动/重置一个定时器（bufferSeconds 秒）</li>
 *   <li>如果在定时器到期前又来新消息，重新计时</li>
 *   <li>定时器到期 → 认为对方说完了，合并所有消息后触发 AI Pipeline</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrivateMessageBuffer {

    private final AiProperties aiProps;
    private final AiReplyPipeline aiReplyPipeline;

    /**
     * 每个发送者的消息缓冲区
     * key = senderWxid, value = 缓冲的消息列表
     */
    private final ConcurrentHashMap<String, SenderBuffer> senderBuffers = new ConcurrentHashMap<>();

    /**
     * 定时调度器，用于 debounce
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "private-buffer-scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * 接收一条私聊消息，加入缓冲区
     * <p>
     * 如果 bufferSeconds 内没有新消息到来，自动触发 AI 回复。
     * </p>
     *
     * @param senderWxid 发送者 wxid
     * @param senderNick 发送者昵称
     * @param content    消息内容
     */
    public void accept(String senderWxid, String senderNick, String content) {
        if (senderWxid == null || content == null) return;

        int bufferSeconds = aiProps.getReply().getPrivateChat().getBufferSeconds();

        SenderBuffer buffer = senderBuffers.computeIfAbsent(senderWxid, k -> new SenderBuffer());

        // 追加消息到缓冲区
        synchronized (buffer) {
            buffer.messages.add(content);

            // 取消之前的定时器
            if (buffer.pendingFlush != null) {
                buffer.pendingFlush.cancel(false);
            }

            // 设置新的定时器
            buffer.pendingFlush = scheduler.schedule(
                    () -> flush(senderWxid, senderNick),
                    bufferSeconds,
                    TimeUnit.SECONDS
            );

            log.info("[PrivateBuffer] 缓冲消息 sender={}, content={}, 等待 {}s 后处理",
                    senderNick, content.length() > 30 ? content.substring(0, 30) + "..." : content, bufferSeconds);
        }
    }

    /**
     * 刷新缓冲区：合并消息并触发 AI Pipeline
     */
    private void flush(String senderWxid, String senderNick) {
        SenderBuffer buffer = senderBuffers.remove(senderWxid);
        if (buffer == null) return;

        List<String> messages;
        synchronized (buffer) {
            messages = List.copyOf(buffer.messages);
            buffer.messages.clear();
        }

        if (messages.isEmpty()) return;

        // 合并消息：用换行连接，形成完整上下文
        String combinedContent = String.join("\n", messages);
        log.info("[PrivateBuffer] 聚合完成 sender={}, 消息数={}, 合并内容={}",
                senderNick, messages.size(),
                combinedContent.length() > 80 ? combinedContent.substring(0, 80) + "..." : combinedContent);

        // 触发 AI Pipeline（chatroomId=null 表示私聊）
        try {
            aiReplyPipeline.processGroupMessage(null, senderWxid, senderNick, combinedContent, false);
        } catch (Exception e) {
            log.error("[PrivateBuffer] 触发 Pipeline 失败 sender={}", senderNick, e);
        }
    }

    /**
     * 每个发送者的缓冲状态
     */
    private static class SenderBuffer {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        volatile ScheduledFuture<?> pendingFlush;
    }
}
