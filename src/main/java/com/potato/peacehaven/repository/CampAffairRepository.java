package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.CampAffair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CampAffairRepository extends JpaRepository<CampAffair, Long> {

    /** 按日期降序获取所有记录 */
    List<CampAffair> findAllByOrderByAffairDateDescIdDesc();

    /** 按日期范围查询，按日期降序 */
    List<CampAffair> findByAffairDateBetweenOrderByAffairDateDescIdDesc(LocalDate from, LocalDate to);

    /** 按事务类型查询，按日期降序 */
    List<CampAffair> findByAffairTypeOrderByAffairDateDesc(String affairType);

    /** 按日期查询记录 */
    List<CampAffair> findByAffairDate(LocalDate date);

    /** 按日期和类型查询 */
    List<CampAffair> findByAffairDateAndAffairType(LocalDate date, String affairType);

    /** 删除指定日期的所有记录 */
    void deleteByAffairDate(LocalDate date);

    /** 按日期分组统计各类型参与人数（用于图表） */
    @Query("SELECT a.affairDate, a.affairType, COUNT(a) FROM CampAffair a GROUP BY a.affairDate, a.affairType ORDER BY a.affairDate ASC")
    List<Object[]> countGroupedByDateAndType();

    /** 按日期范围分组统计各类型参与人数 */
    @Query("SELECT a.affairDate, a.affairType, COUNT(a) FROM CampAffair a WHERE a.affairDate BETWEEN :from AND :to GROUP BY a.affairDate, a.affairType ORDER BY a.affairDate ASC")
    List<Object[]> countGroupedByDateAndTypeBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
