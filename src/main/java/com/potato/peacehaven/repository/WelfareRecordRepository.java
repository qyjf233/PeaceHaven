package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.WelfareRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface WelfareRecordRepository extends JpaRepository<WelfareRecord, Long> {

    List<WelfareRecord> findAllByOrderByWelfareDateDescIdDesc();

    List<WelfareRecord> findByWelfareDateBetweenOrderByWelfareDateDescIdDesc(LocalDate from, LocalDate to);

    void deleteByWelfareDate(LocalDate date);

    /** 查询指定类型的最近一条记录日期 */
    @Query("SELECT MAX(w.welfareDate) FROM WelfareRecord w WHERE w.welfareType = :type")
    LocalDate findLatestDateByType(@Param("type") String type);

    /** 按类型和日期查询记录 */
    List<WelfareRecord> findByWelfareTypeAndWelfareDate(String type, LocalDate date);
}
