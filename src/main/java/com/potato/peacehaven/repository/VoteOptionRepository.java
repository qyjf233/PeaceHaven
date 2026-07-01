package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.VoteOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VoteOptionRepository extends JpaRepository<VoteOption, Long> {

    List<VoteOption> findByActivityIdOrderBySortOrderAsc(Long activityId);
}
