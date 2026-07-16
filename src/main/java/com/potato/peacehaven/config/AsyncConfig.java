package com.potato.peacehaven.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池配置
 * <p>
 * 为 AI 回复流水线提供专用线程池，避免与 Web 请求线程池竞争。
 * </p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * AI 回复专用线程池
     * <p>
     * 核心线程 2，最大线程 5，适合群聊回复场景（并发不高但单次耗时较长）。
     * </p>
     */
    @Bean("aiReplyExecutor")
    public Executor aiReplyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ai-reply-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("[Async] AI 回复线程池已满，任务被拒绝"));
        executor.initialize();
        return executor;
    }
}
