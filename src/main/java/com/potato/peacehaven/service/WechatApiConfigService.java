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
 * 优先级：数据库 > 环境变量/yaml
 * <ul>
 *   <li>启动时：若 DB 有记录则用 DB 值覆盖 Properties；否则将 env 值写入 DB</li>
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
     * 启动时加载配置
     * <p>优先级：配置文件(env/yaml) > 数据库
     * <ul>
     *   <li>配置文件有值 → 同步到 DB（覆盖旧值）</li>
     *   <li>配置文件无值但 DB 有记录 → 用 DB 值</li>
     *   <li>都没有 → 等待管理员配置</li>
     * </ul>
     */
    @PostConstruct
    public void init() {
        Optional<WechatApiConfig> dbConfig = configRepo.findFirstByOrderByIdAsc();

        if (props.isConfigured()) {
            // 配置文件有值 → 以配置文件为准，同步到 DB
            syncToDb();
            log.info("WechatApi 配置已从加载配置文件同步到数据库");
        } else if (dbConfig.isPresent()) {
            // 配置文件无值但 DB 有 → 用 DB 值
            applyToProperties(dbConfig.get());
            log.info("WechatApi 配置文件未设置，已从数据库加载");
        } else {
            log.info("WechatApi 配置未找到（配置文件和数据库均为空），等待管理员配置");
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

    /** 将当前 Properties 值同步到 DB（新增或更新） */
    private void syncToDb() {
        WechatApiConfig cfg = configRepo.findFirstByOrderByIdAsc()
                .orElse(WechatApiConfig.builder().build());
        cfg.setBaseUrl(props.getBaseUrl());
        cfg.setToken(props.getToken());
        cfg.setAppId(props.getAppId());
        cfg.setCallbackUrl(props.getCallbackUrl());
        cfg.setGroupId(props.getGroupId());
        configRepo.save(cfg);
    }

    /** 将 DB 配置值应用到 Properties（热生效） */
    private void applyToProperties(WechatApiConfig cfg) {
        props.setBaseUrl(cfg.getBaseUrl());
        props.setToken(cfg.getToken());
        props.setAppId(cfg.getAppId());
        props.setCallbackUrl(cfg.getCallbackUrl());
        props.setGroupId(cfg.getGroupId());
    }

    private String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }
}
