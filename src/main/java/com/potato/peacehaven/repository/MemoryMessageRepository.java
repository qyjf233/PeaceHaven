package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.MemoryMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryMessageRepository extends JpaRepository<MemoryMessage, Long> {

    List<MemoryMessage> findByStatusOrderByApprovedAtDesc(String status);

    List<MemoryMessage> findByStatusOrderByCreatedAtDesc(String status);

    List<MemoryMessage> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
