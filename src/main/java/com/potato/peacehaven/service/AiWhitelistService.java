package com.potato.peacehaven.service;

import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.entity.BotAiWhitelist;
import com.potato.peacehaven.repository.BotAiWhitelistRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * AI 分身白名单服务
 * <p>
 * 维护两组内存缓存（训练 / 回复），每次白名单变更时刷新，
 * 避免每条消息都查 DB。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiWhitelistService {

    private final BotAiWhitelistRepository whitelistRepo;
    private final AiProperties aiProperties;

    // ─── 训练缓存（记录聊天数据用于 RAG）───
    private final Set<String> trainingGroupIds = new CopyOnWriteArraySet<>();

    // ─── 回复缓存（AI 主动回复消息）───
    private final Set<String> replyGroupIds = new CopyOnWriteArraySet<>();
    private final Set<String> replyFriendWxids = new CopyOnWriteArraySet<>();

    /** 启动时加载缓存并输出 AI 系统诊断信息 */
    @PostConstruct
    public void init() {
        refreshCache();
        log.info("[AiWhitelist] 初始化完成，训练群 {} 个，回复群 {} 个，回复好友 {} 个",
                trainingGroupIds.size(), replyGroupIds.size(), replyFriendWxids.size());
        log.info("[AiWhitelist] 训练群ID: {}", trainingGroupIds);
        log.info("[AiWhitelist] 回复群ID: {}", replyGroupIds);
        log.info("[AiWhitelist] 回复好友wxid: {}", replyFriendWxids);
        log.info("[AiSystem] AI分身就绪状态: enabled={}, apiKey={}, baseUrl={}, isReady={}",
                aiProperties.isEnabled(),
                aiProperties.getLlm().getApiKey() != null && !aiProperties.getLlm().getApiKey().isBlank(),
                aiProperties.getLlm().getBaseUrl(),
                aiProperties.isReady());
    }

    /** 从 DB 刷新内存缓存 */
    private void refreshCache() {
        List<BotAiWhitelist> all = whitelistRepo.findAll();

        Set<String> tGroups = all.stream()
                .filter(e -> "group".equals(e.getType()) && Boolean.TRUE.equals(e.getTrainingEnabled()))
                .map(BotAiWhitelist::getWxid)
                .collect(Collectors.toSet());

        Set<String> rGroups = all.stream()
                .filter(e -> "group".equals(e.getType()) && Boolean.TRUE.equals(e.getReplyEnabled()))
                .map(BotAiWhitelist::getWxid)
                .collect(Collectors.toSet());

        Set<String> rFriends = all.stream()
                .filter(e -> "friend".equals(e.getType()) && Boolean.TRUE.equals(e.getReplyEnabled()))
                .map(BotAiWhitelist::getWxid)
                .collect(Collectors.toSet());

        trainingGroupIds.clear();
        trainingGroupIds.addAll(tGroups);
        replyGroupIds.clear();
        replyGroupIds.addAll(rGroups);
        replyFriendWxids.clear();
        replyFriendWxids.addAll(rFriends);
    }

    // ─── 训练查询 ───────────────────────────────────────

    /** 群消息是否启用训练（记录聊天数据） */
    public boolean isGroupTrainingAllowed(String chatroomId) {
        return chatroomId != null && trainingGroupIds.contains(chatroomId);
    }

    // ─── 回复查询 ───────────────────────────────────────

    /** 群消息是否启用 AI 回复 */
    public boolean isGroupReplyAllowed(String chatroomId) {
        return chatroomId != null && replyGroupIds.contains(chatroomId);
    }

    /** 私聊消息是否启用 AI 回复 */
    public boolean isFriendReplyAllowed(String senderWxid) {
        return senderWxid != null && replyFriendWxids.contains(senderWxid);
    }

    // ─── 列表 ─────────────────────────────────────────

    /** 获取全部白名单 */
    public List<BotAiWhitelist> getWhitelist() {
        return whitelistRepo.findAll();
    }

    // ─── 增删改 ─────────────────────────────────────────

    /**
     * 新增白名单条目
     *
     * @param type  group / friend
     * @param wxid  群聊 ID 或好友 wxid
     * @param name  显示名称
     */
    @Transactional
    public BotAiWhitelist addEntry(String type, String wxid, String name) {
        // 已存在则直接返回
        Optional<BotAiWhitelist> existing = whitelistRepo.findByTypeAndWxid(type, wxid);
        if (existing.isPresent()) {
            return existing.get();
        }
        BotAiWhitelist entry = BotAiWhitelist.builder()
                .type(type)
                .wxid(wxid)
                .name(name)
                .trainingEnabled(true)
                .replyEnabled(false)
                .build();
        entry = whitelistRepo.save(entry);
        refreshCache();
        log.info("[AiWhitelist] 新增 type={}, wxid={}, name={}", type, wxid, name);
        return entry;
    }

    /** 删除白名单条目 */
    @Transactional
    public boolean removeEntry(Long id) {
        if (!whitelistRepo.existsById(id)) return false;
        whitelistRepo.deleteById(id);
        refreshCache();
        log.info("[AiWhitelist] 删除 id={}", id);
        return true;
    }

    /**
     * 更新训练/回复开关
     *
     * @param trainingEnabled 训练开关（null 表示不修改）
     * @param replyEnabled    回复开关（null 表示不修改）
     */
    @Transactional
    public boolean updateFlags(Long id, Boolean trainingEnabled, Boolean replyEnabled) {
        return whitelistRepo.findById(id).map(entry -> {
            if (trainingEnabled != null) entry.setTrainingEnabled(trainingEnabled);
            if (replyEnabled != null) entry.setReplyEnabled(replyEnabled);
            whitelistRepo.save(entry);
            refreshCache();
            log.info("[AiWhitelist] 更新 id={}, training={}, reply={}", id,
                    entry.getTrainingEnabled(), entry.getReplyEnabled());
            return true;
        }).orElse(false);
    }
}
