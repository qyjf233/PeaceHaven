package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.PageVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PageVisitRepository extends JpaRepository<PageVisit, Long> {

    /** 按日期统计每日PV（按天分组计数） */
    @Query("SELECT CAST(v.createdAt AS DATE), COUNT(v) FROM PageVisit v " +
            "WHERE v.createdAt BETWEEN :from AND :to " +
            "GROUP BY CAST(v.createdAt AS DATE) ORDER BY CAST(v.createdAt AS DATE)")
    List<Object[]> dailyPv(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按日期统计每日UV（按天+IP去重计数） */
    @Query("SELECT CAST(v.createdAt AS DATE), COUNT(DISTINCT v.ip) FROM PageVisit v " +
            "WHERE v.createdAt BETWEEN :from AND :to " +
            "GROUP BY CAST(v.createdAt AS DATE) ORDER BY CAST(v.createdAt AS DATE)")
    List<Object[]> dailyUv(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 页面热度排行（按页面分组计数，取Top N） */
    @Query("SELECT v.page, COUNT(v) FROM PageVisit v " +
            "WHERE v.createdAt BETWEEN :from AND :to " +
            "GROUP BY v.page ORDER BY COUNT(v) DESC")
    List<Object[]> pageRanking(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 访问最多的IP排行 */
    @Query("SELECT v.ip, COUNT(v) FROM PageVisit v " +
            "WHERE v.createdAt BETWEEN :from AND :to " +
            "GROUP BY v.ip ORDER BY COUNT(v) DESC")
    List<Object[]> topIps(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 来源域名排行 */
    @Query("SELECT v.referer, COUNT(v) FROM PageVisit v " +
            "WHERE v.createdAt BETWEEN :from AND :to AND v.referer IS NOT NULL AND v.referer <> '' " +
            "GROUP BY v.referer ORDER BY COUNT(v) DESC")
    List<Object[]> topReferers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 指定范围内的总访问数 */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    /** 指定范围内的独立IP数 */
    @Query("SELECT COUNT(DISTINCT v.ip) FROM PageVisit v WHERE v.createdAt BETWEEN :from AND :to")
    long countDistinctIpBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
