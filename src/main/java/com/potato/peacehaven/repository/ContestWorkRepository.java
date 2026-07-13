package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.ContestWork;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContestWorkRepository extends JpaRepository<ContestWork, Long> {

    /** 查询指定活动的已通过审核作品 */
    List<ContestWork> findByActivityIdAndStatus(
            Long activityId, ContestWork.WorkStatus status);

    /** 查询指定用户在指定活动的投稿 */
    Optional<ContestWork> findByActivityIdAndUserId(Long activityId, Long userId);

    /** 查询指定活动的所有投稿 */
    List<ContestWork> findByActivityIdOrderByCreatedAtDesc(Long activityId);

    /** 分页查询指定活动的作品（按状态筛选） */
    Page<ContestWork> findByActivityIdAndStatusOrderByCreatedAtDesc(
            Long activityId, ContestWork.WorkStatus status, Pageable pageable);

    /** 分页查询指定活动的所有作品 */
    Page<ContestWork> findByActivityIdOrderByCreatedAtDesc(Long activityId, Pageable pageable);

    /** 统计指定活动的作品数量 */
    long countByActivityId(Long activityId);

    /** 统计指定活动指定状态的作品数量 */
    long countByActivityIdAndStatus(Long activityId, ContestWork.WorkStatus status);
}
