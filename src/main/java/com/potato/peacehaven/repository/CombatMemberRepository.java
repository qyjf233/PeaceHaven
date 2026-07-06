package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.CombatMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CombatMemberRepository extends JpaRepository<CombatMember, Long> {

    /**
     * 按排序权重升序获取所有战斗组成员
     */
    List<CombatMember> findAllByOrderBySortOrderAsc();

    /**
     * 按用户ID查找战斗组成员
     */
    Optional<CombatMember> findByUserId(Long userId);

    /**
     * 检查某用户是否已在战斗组中
     */
    boolean existsByUserId(Long userId);
}
