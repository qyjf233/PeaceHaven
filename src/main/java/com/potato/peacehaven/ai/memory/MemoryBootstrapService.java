package com.potato.peacehaven.ai.memory;

import com.potato.peacehaven.entity.BotChatRecord;
import com.potato.peacehaven.entity.BotUserMemory;
import com.potato.peacehaven.repository.BotChatRecordRepository;
import com.potato.peacehaven.repository.BotUserMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 用户画像补课服务
 * <p>
 * 从历史聊天记录（bot_chat_record）中批量提取用户画像，
 * 补建因之前 bug 或只读模式（only-at）而缺失的用户记忆。
 * </p>
 * <p>
 * 资源保护措施：
 * <ul>
 *   <li>每个用户之间间隔 1 秒，避免 LLM API 限流</li>
 *   <li>跳过已有画像的用户（除非 force=true）</li>
 *   <li>单用户最多处理 50 条消息</li>
 *   <li>同一时间只允许一个补课任务运行</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryBootstrapService {

    private final BotChatRecordRepository chatRecordRepo;
    private final BotUserMemoryRepository memoryRepo;
    private final UserMemoryExtractor memoryExtractor;

    /** 单用户最多处理消息数 */
    private static final int MAX_MESSAGES_PER_USER = 50;

    /** 用户间处理间隔（毫秒） */
    private static final long DELAY_BETWEEN_USERS_MS = 1000;

    /** 防止并发执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 启动画像补课
     *
     * @param force 是否强制重新处理已有画像的用户
     * @return 执行结果摘要
     */
    public Map<String, Object> bootstrap(boolean force) {
        if (!running.compareAndSet(false, true)) {
            return Map.of("success", false, "message", "已有补课任务在运行中");
        }

        long startTime = System.currentTimeMillis();
        List<String> allSenders = chatRecordRepo.findDistinctNonSelfSenderWxids();
        Set<String> existingWxids = memoryRepo.findAll().stream()
                .map(BotUserMemory::getWxid)
                .collect(Collectors.toSet());

        // 过滤：非 force 模式跳过已有画像的用户
        List<String> toProcess = allSenders.stream()
                .filter(wxid -> force || !existingWxids.contains(wxid))
                .collect(Collectors.toList());

        log.info("[MemoryBootstrap] 开始补课: 总发送者={}, 待处理={}, force={}",
                allSenders.size(), toProcess.size(), force);

        int processed = 0;
        int extracted = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (String wxid : toProcess) {
            try {
                List<BotChatRecord> records = chatRecordRepo
                        .findBySenderWxidAndIsSelfFalseAndIsBotReplyFalseOrderByCreateTimeAsc(
                                wxid, PageRequest.of(0, MAX_MESSAGES_PER_USER));

                if (records.isEmpty()) {
                    skipped++;
                    continue;
                }

                // 取最近一条的昵称作为当前昵称
                String nick = records.stream()
                        .map(BotChatRecord::getSenderNick)
                        .filter(Objects::nonNull)
                        .reduce((a, b) -> b) // 取最后一个非空的
                        .orElse(wxid);

                // force 模式：先清除自动提取的记忆，保留手动添加的
                if (force) {
                    try {
                        var existingMemory = memoryRepo.findByWxid(wxid);
                        if (existingMemory.isPresent()) {
                            var mem = existingMemory.get();
                            var existing = mem.getStructuredMemories();
                            if (existing != null && !existing.isEmpty()) {
                                var manualOnly = existing.stream()
                                        .filter(com.potato.peacehaven.ai.memory.MemoryEntry::isManual)
                                        .collect(Collectors.toList());
                                mem.setStructuredMemories(new ArrayList<>(manualOnly));
                                memoryRepo.save(mem);
                                log.debug("[MemoryBootstrap] force 清除自动记忆 wxid={}, 保留 manual={}",
                                        wxid, manualOnly.size());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[MemoryBootstrap] 清除旧记忆失败 wxid={}: {}", wxid, e.getMessage());
                    }
                }

                // 逐条消息提取记忆（模拟实时 Pipeline 的行为）
                List<String> contextBuffer = new ArrayList<>();
                for (BotChatRecord record : records) {
                    String content = record.getContent();
                    if (content == null || content.isBlank()) continue;

                    // 构建上下文（最多保留最近 5 条）
                    List<String> recentContext = contextBuffer.size() > 5
                            ? contextBuffer.subList(contextBuffer.size() - 5, contextBuffer.size())
                            : new ArrayList<>(contextBuffer);

                    try {
                        memoryExtractor.extractAndUpdate(
                                wxid, nick, content, null, recentContext);
                    } catch (Exception e) {
                        log.debug("[MemoryBootstrap] 单条提取失败 wxid={}: {}", wxid, e.getMessage());
                    }

                    contextBuffer.add(nick + ": " + content);
                }

                processed++;
                log.info("[MemoryBootstrap] 处理完成 {}/{}: wxid={}, nick={}, 消息数={}",
                        processed, toProcess.size(), wxid, nick, records.size());

                // 用户间延迟，保护 LLM API
                if (processed < toProcess.size()) {
                    Thread.sleep(DELAY_BETWEEN_USERS_MS);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add("任务被中断");
                break;
            } catch (Exception e) {
                errors.add(wxid + ": " + e.getMessage());
                log.warn("[MemoryBootstrap] 用户处理失败 wxid={}: {}", wxid, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        running.set(false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("totalSenders", allSenders.size());
        result.put("processed", processed);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("elapsedMs", elapsed);
        result.put("elapsedReadable", String.format("%.1f秒", elapsed / 1000.0));

        log.info("[MemoryBootstrap] 补课完成: processed={}, skipped={}, errors={}, 耗时={}ms",
                processed, skipped, errors.size(), elapsed);

        return result;
    }

    /**
     * 当前是否有补课任务在运行
     */
    public boolean isRunning() {
        return running.get();
    }
}
