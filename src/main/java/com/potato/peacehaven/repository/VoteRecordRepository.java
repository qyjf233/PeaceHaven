package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.VoteRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VoteRecordRepository extends JpaRepository<VoteRecord, Long> {

    Optional<VoteRecord> findByActivityIdAndVoterName(Long activityId, String voterName);

    boolean existsByActivityIdAndVoterName(Long activityId, String voterName);
}
