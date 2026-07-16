package com.potato.peacehaven.ai.memory;

import com.potato.peacehaven.entity.BotUserMemory;
import com.potato.peacehaven.repository.BotUserMemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务
 * <p>
 * 管理聊天对象画像（summary / tags / facts），
 * AI 回复时加载对方画像，使回复更具针对性。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final BotUserMemoryRepository memoryRepo;

    /**
     * 查询用户画像
     */
    public Optional<BotUserMemory> getUserMemory(String wxid) {
        if (wxid == null || wxid.isBlank()) return Optional.empty();
        return memoryRepo.findByWxid(wxid);
    }

    /**
     * 批量查询用户画像
     */
    public List<BotUserMemory> getUserMemories(List<String> wxids) {
        if (wxids == null || wxids.isEmpty()) return List.of();
        return memoryRepo.findByWxidIn(wxids);
    }

    /**
     * 创建或更新用户画像
     */
    public BotUserMemory saveOrUpdate(String wxid, String nickname, String summary,
                                       List<String> tags, List<String> facts) {
        BotUserMemory memory = memoryRepo.findByWxid(wxid)
                .orElse(BotUserMemory.builder().wxid(wxid).build());

        if (nickname != null) memory.setNickname(nickname);
        if (summary != null) memory.setSummary(summary);
        if (tags != null) memory.setTags(tags);
        if (facts != null) memory.setFacts(facts);

        memoryRepo.save(memory);
        log.debug("[UserMemory] 保存画像 wxid={}, nick={}", wxid, nickname);
        return memory;
    }

    /**
     * 保存结构化记忆 + 关系字段
     */
    public BotUserMemory saveStructured(String wxid, String nickname,
                                         List<MemoryEntry> structuredMemories,
                                         String summary) {
        BotUserMemory memory = memoryRepo.findByWxid(wxid)
                .orElse(BotUserMemory.builder().wxid(wxid).build());

        if (nickname != null) memory.setNickname(nickname);
        if (structuredMemories != null) memory.setStructuredMemories(structuredMemories);
        if (summary != null) memory.setSummary(summary);

        memoryRepo.save(memory);
        log.debug("[UserMemory] 保存结构化记忆 wxid={}, entries={}",
                wxid, structuredMemories != null ? structuredMemories.size() : 0);
        return memory;
    }

    /**
     * 将用户画像格式化为 prompt 片段
     * <p>
     * 输出示例：
     * <pre>
     * 聊天对象「小明」的信息：
     * - 画像：一个喜欢打游戏的程序员
     * - 标签：Java, 杭州, LOL
     * - 了解的事实：养了一只猫叫小花, 喜欢深夜写代码
     * </pre>
     * </p>
     */
    public String formatMemoryForPrompt(BotUserMemory memory) {
        if (memory == null) return "";

        StringBuilder sb = new StringBuilder();
        String name = memory.getNickname() != null ? memory.getNickname() : memory.getWxid();
        sb.append("聊天对象「").append(name).append("」的信息：\n");

        if (memory.getSummary() != null && !memory.getSummary().isBlank()) {
            sb.append("- 画像：").append(memory.getSummary()).append("\n");
        }

        if (memory.getTags() != null && !memory.getTags().isEmpty()) {
            sb.append("- 标签：").append(String.join(", ", memory.getTags())).append("\n");
        }

        if (memory.getFacts() != null && !memory.getFacts().isEmpty()) {
            sb.append("- 了解的事实：").append(String.join(", ", memory.getFacts())).append("\n");
        }

        return sb.toString();
    }

    /**
     * 批量格式化用户画像为 prompt 片段
     */
    public String formatMemoriesForPrompt(List<BotUserMemory> memories) {
        if (memories == null || memories.isEmpty()) return "";
        return memories.stream()
                .map(this::formatMemoryForPrompt)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
