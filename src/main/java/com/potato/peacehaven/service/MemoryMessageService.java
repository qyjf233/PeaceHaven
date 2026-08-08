package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.MemoryMessage;
import com.potato.peacehaven.repository.MemoryMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryMessageService {

    private final MemoryMessageRepository messageRepository;

    /**
     * 游客提交留言（状态 PENDING，需管理员审核）
     */
    @Transactional
    public MemoryMessage create(String nickname, String content) {
        if (nickname == null || nickname.isBlank()) throw new IllegalArgumentException("昵称不能为空");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("留言内容不能为空");
        if (nickname.length() > 50) throw new IllegalArgumentException("昵称过长");
        if (content.length() > 500) throw new IllegalArgumentException("留言内容过长（最多500字）");

        MemoryMessage msg = MemoryMessage.builder()
                .nickname(nickname.trim())
                .content(content.trim())
                .status("PENDING")
                .build();
        messageRepository.save(msg);
        log.info("[留言板] 新留言待审核: {} - {}", msg.getNickname(), msg.getContent().substring(0, Math.min(30, msg.getContent().length())));
        return msg;
    }

    /**
     * 管理员通过留言
     */
    @Transactional
    public void approve(Long id) {
        MemoryMessage msg = messageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("留言不存在"));
        msg.setStatus("APPROVED");
        msg.setApprovedAt(LocalDateTime.now());
        messageRepository.save(msg);
        log.info("[留言板] 留言 {} 已审核通过", id);
    }

    /**
     * 管理员拒绝留言
     */
    @Transactional
    public void reject(Long id) {
        MemoryMessage msg = messageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("留言不存在"));
        msg.setStatus("REJECTED");
        messageRepository.save(msg);
        log.info("[留言板] 留言 {} 已拒绝", id);
    }

    /**
     * 删除留言
     */
    @Transactional
    public void delete(Long id) {
        messageRepository.deleteById(id);
    }

    /**
     * 获取已审核通过的留言（按审核时间倒序）
     */
    public List<MemoryMessage> getApprovedMessages() {
        return messageRepository.findByStatusOrderByApprovedAtDesc("APPROVED");
    }

    /**
     * 获取待审核留言
     */
    public List<MemoryMessage> getPendingMessages() {
        return messageRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    /**
     * 获取所有留言（管理用）
     */
    public List<MemoryMessage> getAllMessages() {
        return messageRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 待审核数量
     */
    public long getPendingCount() {
        return messageRepository.countByStatus("PENDING");
    }
}
