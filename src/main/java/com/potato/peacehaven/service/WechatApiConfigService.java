package com.potato.peacehaven.service;

import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.entity.WechatApiConfig;
import com.potato.peacehaven.repository.WechatApiConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * WechatApi 配置管理服务
 * <p>
 * 配置唯一来源：数据库
 * <ul>
 *   <li>启动时：从 DB 加载到 Properties（运行时缓存）</li>
 *   <li>保存时：更新 DB 并刷新 Properties（热生效，无需重启）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatApiConfigService {

    private final WechatApiConfigRepository configRepo;
    private final WechatApiProperties props;

    /**
     * 启动时从数据库加载配置
     */
    @PostConstruct
    public void init() {
        Optional<WechatApiConfig> dbConfig = configRepo.findFirstByOrderByIdAsc();
        if (dbConfig.isPresent()) {
            applyToProperties(dbConfig.get());
            log.info("WechatApi 配置已从数据库加载");
        } else {
            log.info("WechatApi 数据库无配置，等待管理员通过后台配置");
        }
    }

    /** 获取当前 DB 中的配置（可能为 null） */
    public WechatApiConfig getConfig() {
        return configRepo.findFirstByOrderByIdAsc().orElse(null);
    }

    /** 更新 appId（登录成功后调用） */
    public void updateAppId(String newAppId) {
        WechatApiConfig cfg = configRepo.findFirstByOrderByIdAsc()
                .orElse(WechatApiConfig.builder().build());
        cfg.setAppId(newAppId);
        configRepo.save(cfg);
        props.setAppId(newAppId);
        log.info("WechatApi appId 已更新: {}", newAppId);
    }

    /**
     * 保存配置到 DB 并热更新 Properties
     */
    public WechatApiConfig saveConfig(String baseUrl, String token, String appId,
                                       String callbackUrl, String groupId) {
        WechatApiConfig cfg = configRepo.findFirstByOrderByIdAsc()
                .orElse(WechatApiConfig.builder().build());

        cfg.setBaseUrl(blankToNull(baseUrl));
        cfg.setToken(blankToNull(token));
        cfg.setAppId(blankToNull(appId));
        cfg.setCallbackUrl(blankToNull(callbackUrl));
        cfg.setGroupId(blankToNull(groupId));

        cfg = configRepo.save(cfg);
        applyToProperties(cfg);
        log.info("WechatApi 配置已保存并热更新");
        return cfg;
    }

    /**
     * 设置定时推送开关
     */
    public void setPushEnabled(Boolean enabled) {
        WechatApiConfig cfg = configRepo.findFirstByOrderByIdAsc()
                .orElse(WechatApiConfig.builder().build());
        cfg.setPushEnabled(enabled);
        configRepo.save(cfg);
        props.setPushEnabled(enabled);
        log.info("定时推送已{}", Boolean.TRUE.equals(enabled) ? "开启" : "关闭");
    }

    /** 将 DB 配置值应用到 Properties（热生效） */
    private void applyToProperties(WechatApiConfig cfg) {
        props.setBaseUrl(cfg.getBaseUrl());
        props.setToken(cfg.getToken());
        props.setAppId(cfg.getAppId());
        props.setCallbackUrl(cfg.getCallbackUrl());
        props.setGroupId(cfg.getGroupId());
        props.setPushEnabled(cfg.getPushEnabled());
    }

    private String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }
}
