package com.potato.peacehaven.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

/**
 * WechatApi 配置属性（运行时缓存）
 * <p>值来源于数据库，由 {@link com.potato.peacehaven.service.WechatApiConfigService} 加载</p>
 */
@Getter
@Setter
@Component
public class WechatApiProperties {

    /** API 基础地址（https://api.wechatapi.net/finder） */
    private String baseUrl;

    /** 账户级鉴权 Token，放在请求头 wechat-token 中 */
    private String token;

    /** 设备 ID（appId），标识具体哪个设备的会话 */
    private String appId;

    /** Webhook 回调地址（WechatApi 收到消息后推送到此地址） */
    private String callbackUrl;

    /** 目标群聊 ID（格式：xxxxxxxx@chatroom） */
    private String groupId;

    /** 是否启用定时推送 */
    private Boolean pushEnabled = true;

    /**
     * 判断配置是否可用（baseUrl 和 token 均非空）
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && token != null && !token.isBlank();
    }

    /**
     * 判断设备是否绑定（appId 非空）
     */
    public boolean isDeviceBound() {
        return appId != null && !appId.isBlank();
    }

    /**
     * 判断定时推送是否启用
     */
    public boolean isPushEnabled() {
        return pushEnabled != null && pushEnabled;
    }
}
