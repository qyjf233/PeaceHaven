package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotGroupMemberRepository extends JpaRepository<BotGroupMember, Long> {

    Optional<BotGroupMember> findByWxid(String wxid);

    void deleteByWxidNotIn(List<String> wxids);
}
