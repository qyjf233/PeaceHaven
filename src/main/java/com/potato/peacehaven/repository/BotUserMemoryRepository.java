package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.BotUserMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotUserMemoryRepository extends JpaRepository<BotUserMemory, Long> {

    Optional<BotUserMemory> findByWxid(String wxid);

    List<BotUserMemory> findByWxidIn(List<String> wxids);

    boolean existsByWxid(String wxid);

    void deleteByWxid(String wxid);
}
