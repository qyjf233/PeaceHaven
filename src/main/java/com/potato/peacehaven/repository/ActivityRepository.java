package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.enums.ActivityStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long> {

    Page<Activity> findByStatusOrderByStartDateDesc(ActivityStatus status, Pageable pageable);

    List<Activity> findTop4ByStatusInOrderByStartDateDesc(List<ActivityStatus> statuses);

    Page<Activity> findAllByOrderByStartDateDesc(Pageable pageable);
}
