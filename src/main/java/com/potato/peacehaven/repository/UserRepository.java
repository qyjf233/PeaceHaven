package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    List<User> findByRole(UserRole role);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<User> findByRoleOrderByCreatedAtDesc(UserRole role, Pageable pageable);

    /** 查询已有的营地名称（去重，前缀匹配） */
    @Query("SELECT DISTINCT u.campName FROM User u WHERE u.campName IS NOT NULL AND u.campName <> '' AND LOWER(u.campName) LIKE LOWER(CONCAT(:prefix, '%'))")
    List<String> findDistinctCampNamesByPrefix(@Param("prefix") String prefix);
}
