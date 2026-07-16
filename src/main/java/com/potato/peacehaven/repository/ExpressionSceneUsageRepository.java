package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.ExpressionSceneUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExpressionSceneUsageRepository extends JpaRepository<ExpressionSceneUsage, Long> {

    /** 查找某表达在所有场景的使用计数 */
    List<ExpressionSceneUsage> findByExpressionId(Long expressionId);

    /** 查找某表达在某场景的使用计数 */
    Optional<ExpressionSceneUsage> findByExpressionIdAndSceneType(Long expressionId, String sceneType);

    /** 删除某表达的所有场景计数 */
    void deleteByExpressionId(Long expressionId);
}
