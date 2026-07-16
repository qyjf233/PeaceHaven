package com.potato.peacehaven.service;

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
 * 维护内存缓存（groupIds / friendWxids），每次白名单变更时刷新，
 * 避免每条消息都查 DB。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiWhitelistService {

    private final BotAiWhitelistRepository whitelistRepo;

    /** 启用的群聊 ID 集合 */
    private final Set<String> allowedGroupIds = new CopyOnWriteArraySet<>();

    /** 启用的好友 wxid 集合 */
    private final Set<String> allowedFriendWxids = new CopyOnWriteArraySet<>();

    /** 启动时加载缓存 */
    @PostConstruct
    public void init() {
        refreshCache();
        log.info("[AiWhitelist] 初始化完成，群聊 {} 个，好友 {} 个",
                allowedGroupIds.size(), allowedFriendWxids.size());
    }

    /** 从 DB 刷新内存缓存 */
    private void refreshCache() {
        List<BotAiWhitelist> enabled = whitelistRepo.findAllByEnabledTrue();
        Set<String> groups = enabled.stream()
                .filter(e -> "group".equals(e.getType()))
                .map(BotAiWhitelist::getWxid)
                .collect(Collectors.toSet());
        Set<String> friends = enabled.stream()
                .filter(e -> "friend".equals(e.getType()))
                .map(BotAiWhitelist::getWxid)
                .collect(Collectors.toSet());

        allowedGroupIds.clear();
        allowedGroupIds.addAll(groups);
        allowedFriendWxids.clear();
        allowedFriendWxids.addAll(friends);
    }

    // ─── 查询 ───────────────────────────────────────────

    /**
     * 判断群消息是否允许触发 AI
     *
     * @param chatroomId 群聊 ID（xxx@chatroom）
     */
    public boolean isGroupAllowed(String chatroomId) {
        return chatroomId != null && allowedGroupIds.contains(chatroomId);
    }

    /**
     * 判断私聊消息是否允许触发 AI
     *
     * @param senderWxid 发送者 wxid
     */
    public boolean isFriendAllowed(String senderWxid) {
        return senderWxid != null && allowedFriendWxids.contains(senderWxid);
    }

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
        // 已存在则返回
        Optional<BotAiWhitelist> existing = whitelistRepo.findByTypeAndWxid(type, wxid);
        if (existing.isPresent()) {
            BotAiWhitelist e = existing.get();
            if (!Boolean.TRUE.equals(e.getEnabled())) {
                e.setEnabled(true);
                e.setName(name);
                e = whitelistRepo.save(e);
                refreshCache();
                log.info("[AiWhitelist] 重新启用 type={}, wxid={}, name={}", type, wxid, name);
                return e;
            }
            return e;
        }
        BotAiWhitelist entry = BotAiWhitelist.builder()
                .type(type)
                .wxid(wxid)
                .name(name)
                .enabled(true)
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

    /** 切换启用/停用 */
    @Transactional
    public boolean toggleEntry(Long id, boolean enabled) {
        return whitelistRepo.findById(id).map(entry -> {
            entry.setEnabled(enabled);
            whitelistRepo.save(entry);
            refreshCache();
            log.info("[AiWhitelist] 切换 id={}, enabled={}", id, enabled);
            return true;
        }).orElse(false);
    }
}
