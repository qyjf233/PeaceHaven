package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotEmojiLibrary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BotEmojiLibraryRepository extends JpaRepository<BotEmojiLibrary, Long> {

    /** 按 MD5 查找表情包（唯一标识） */
    Optional<BotEmojiLibrary> findByMd5(String md5);

    /** 是否存在该 MD5 的表情包 */
    boolean existsByMd5(String md5);

    /** 查找未标注且使用次数达到阈值的表情包（待标注候选） */
    @Query("SELECT e FROM BotEmojiLibrary e WHERE e.labeled = false AND e.usageCount >= :minUsage ORDER BY e.usageCount DESC")
    List<BotEmojiLibrary> findUnlabeledWithMinUsage(int minUsage);

    /** 查找已标注的表情包（用于查询/展示） */
    List<BotEmojiLibrary> findByLabeledTrue();

    /** 按标签模糊搜索（用于发送时匹配表情包） */
    @Query("SELECT e FROM BotEmojiLibrary e WHERE e.labeled = true AND e.tags LIKE %:keyword% ORDER BY e.usageCount DESC")
    List<BotEmojiLibrary> findByTagKeyword(String keyword);
}
