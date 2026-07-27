package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.DraftPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DraftPickRepository extends JpaRepository<DraftPick, Long> {

    /** 查询某场比赛的所有选人记录 */
    List<DraftPick> findByActivityIdAndMatchIndexOrderByPickOrderAsc(Long activityId, Integer matchIndex);

    /** 查询某场比赛某队伍的选人记录 */
    List<DraftPick> findByActivityIdAndMatchIndexAndTeamSideOrderByPickOrderAsc(
            Long activityId, Integer matchIndex, String teamSide);

    /** 检查某场比赛某个玩家是否已被选 */
    boolean existsByActivityIdAndMatchIndexAndUserId(Long activityId, Integer matchIndex, Long userId);

    /** 统计某场比赛某队伍的已选人数 */
    long countByActivityIdAndMatchIndexAndTeamSide(Long activityId, Integer matchIndex, String teamSide);

    /** 删除某场比赛的所有选人记录（用于重置） */
    void deleteByActivityIdAndMatchIndex(Long activityId, Integer matchIndex);
}
