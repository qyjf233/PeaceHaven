package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.UserManualStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserManualStatusRepository extends JpaRepository<UserManualStatus, Long> {

    /**
     * 查询当前有效的状态（未过期 或 永不过期）
     */
    @Query("SELECT s FROM UserManualStatus s WHERE s.expiresAt IS NULL OR s.expiresAt > :now")
    List<UserManualStatus> findActiveStatuses(@Param("now") LocalDateTime now);
}
