package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.LearnedStyleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LearnedStyleConfigRepository extends JpaRepository<LearnedStyleConfig, Long> {
}
