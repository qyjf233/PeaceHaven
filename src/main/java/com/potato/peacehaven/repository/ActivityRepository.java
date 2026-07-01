package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.Activity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long> {

    /** 根据slug查找活动 */
    Optional<Activity> findBySlug(String slug);

    /** 查询进行中的活动（当前时间在开始和结束之间） */
    Page<Activity> findByStartDateBeforeAndEndDateAfter(LocalDateTime now1, LocalDateTime now2, Pageable pageable);

    /** 查询即将开始/进行中的活动（结束时间在当前时间之后） */
    List<Activity> findTop4ByEndDateAfterOrderByStartDateAsc(LocalDateTime now);

    /** 分页查询所有活动，按开始时间倒序 */
    Page<Activity> findAllByOrderByStartDateDesc(Pageable pageable);
}
