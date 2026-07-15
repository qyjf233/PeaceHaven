package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.WechatApiConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WechatApiConfigRepository extends JpaRepository<WechatApiConfig, Long> {

    /** 取第一条配置（单行表） */
    Optional<WechatApiConfig> findFirstByOrderByIdAsc();
}
