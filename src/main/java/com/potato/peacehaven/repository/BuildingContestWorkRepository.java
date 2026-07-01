package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BuildingContestWork;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BuildingContestWorkRepository extends JpaRepository<BuildingContestWork, Long> {

    /** 查询指定活动的已通过审核作品，按票数降序 */
    List<BuildingContestWork> findByActivityIdAndStatusOrderByVoteCountDesc(
            Long activityId, BuildingContestWork.WorkStatus status);

    /** 查询指定用户在指定活动的投稿 */
    Optional<BuildingContestWork> findByActivityIdAndUserId(Long activityId, Long userId);

    /** 查询指定活动的所有投稿 */
    List<BuildingContestWork> findByActivityIdOrderByCreatedAtDesc(Long activityId);

    /** 分页查询指定活动的作品（按状态筛选） */
    Page<BuildingContestWork> findByActivityIdAndStatusOrderByCreatedAtDesc(
            Long activityId, BuildingContestWork.WorkStatus status, Pageable pageable);

    /** 分页查询指定活动的所有作品 */
    Page<BuildingContestWork> findByActivityIdOrderByCreatedAtDesc(Long activityId, Pageable pageable);
}
