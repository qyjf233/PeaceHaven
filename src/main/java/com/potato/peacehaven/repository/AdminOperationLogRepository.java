package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.AdminOperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminOperationLogRepository extends JpaRepository<AdminOperationLog, Long> {

    /** 按创建时间倒序分页查询所有日志 */
    Page<AdminOperationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按模块筛选，按时间倒序分页 */
    Page<AdminOperationLog> findByModuleOrderByCreatedAtDesc(String module, Pageable pageable);

    /** 按操作人筛选，按时间倒序分页 */
    Page<AdminOperationLog> findByOperatorOrderByCreatedAtDesc(String operator, Pageable pageable);

    /** 按模块+操作人筛选 */
    Page<AdminOperationLog> findByModuleAndOperatorOrderByCreatedAtDesc(String module, String operator, Pageable pageable);
}
