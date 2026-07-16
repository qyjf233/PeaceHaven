package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.PersonaStyleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PersonaStyleSnapshotRepository extends JpaRepository<PersonaStyleSnapshot, Long> {

    /** 查找某时间之后的快照（DriftDetector 使用） */
    List<PersonaStyleSnapshot> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime after);

    /** 查找最新一条快照 */
    List<PersonaStyleSnapshot> findTopByOrderByCreatedAtDesc();
}
