package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.ExpressionProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExpressionProfileRepository extends JpaRepository<ExpressionProfile, Long> {

    Optional<ExpressionProfile> findByPhrase(String phrase);

    /** 查找疲劳度超过阈值的表达（需要避免使用） */
    List<ExpressionProfile> findByFatigueScoreGreaterThan(double threshold);

    /** 查找高频表达（频率 > 阈值） */
    List<ExpressionProfile> findByFrequencyGreaterThanOrderByFrequencyDesc(double threshold);
}
