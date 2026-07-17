package com.potato.peacehaven.config;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 链路追踪上下文工具
 * <p>
 * 基于 SLF4J MDC 实现，key = "traceId"。
 * 每条消息从 webhook 接收到最终回复，生成唯一 traceId，贯穿整个处理链路。
 * 在 SLS 中搜索该 traceId 即可看到完整事件链。
 * </p>
 * <p>
 * 格式：8位时间hex-4位随机hex（如 a1b2c3d4-f5e6），短且足够唯一。
 * </p>
 *
 * <b>注意</b>：MDC 是基于线程的，@Async 异步线程需要显式传递 traceId。
 */
public final class TraceContext {

    public static final String KEY = "traceId";

    private TraceContext() {}

    /**
     * 生成新的 traceId 并设置到 MDC
     *
     * @return 生成的 traceId
     */
    public static String generate() {
        String traceId = String.format("%08x-%04x",
                (int) (System.currentTimeMillis() / 1000),
                ThreadLocalRandom.current().nextInt(0x10000));
        MDC.put(KEY, traceId);
        return traceId;
    }

    /**
     * 将已有的 traceId 设置到当前线程的 MDC（用于异步线程传播）
     *
     * @param traceId 链路追踪 ID
     */
    public static void set(String traceId) {
        if (traceId != null) {
            MDC.put(KEY, traceId);
        }
    }

    /**
     * 获取当前线程的 traceId
     *
     * @return traceId，未设置时返回 null
     */
    public static String get() {
        return MDC.get(KEY);
    }

    /**
     * 清除当前线程的 traceId（必须在 finally 中调用，防止线程池复用导致污染）
     */
    public static void clear() {
        MDC.remove(KEY);
    }
}
