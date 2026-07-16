package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.RelationshipProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RelationshipProfileRepository extends JpaRepository<RelationshipProfile, Long> {

    Optional<RelationshipProfile> findByContactName(String contactName);

    List<RelationshipProfile> findByRelationshipType(String relationshipType);

    /** 查找样本数足够的关系画像（用于 confidence 计算） */
    List<RelationshipProfile> findBySampleCountGreaterThan(int minSamples);
}
