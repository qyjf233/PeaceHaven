package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotAiWhitelist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotAiWhitelistRepository extends JpaRepository<BotAiWhitelist, Long> {

    /** 按 type+wxid 精确查找 */
    Optional<BotAiWhitelist> findByTypeAndWxid(String type, String wxid);

    /** 按类型列出所有条目 */
    List<BotAiWhitelist> findByType(String type);

    /** 获取所有启用的条目 */
    List<BotAiWhitelist> findAllByEnabledTrue();

    /** 快速判断是否在白名单中（启用状态） */
    boolean existsByTypeAndWxidAndEnabledTrue(String type, String wxid);
}
