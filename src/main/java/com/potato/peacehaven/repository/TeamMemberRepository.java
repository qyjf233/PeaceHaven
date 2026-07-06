package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    /**
     * 按排序权重升序获取所有管理组成员
     */
    List<TeamMember> findAllByOrderBySortOrderAsc();

    /**
     * 检查某用户是否为管理组成员
     */
    boolean existsByUserId(Long userId);
}
