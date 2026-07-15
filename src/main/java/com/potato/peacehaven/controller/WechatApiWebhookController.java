package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.service.WechatApiService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * WechatApi Webhook 回调接收端点
 * <p>
 * WechatApi 云端在收到微信消息后，会向此端点 POST 消息 JSON。
 * 此端点不经过管理员鉴权（路径在 /admin 之外），由 WechatApi 服务端直连。
 * <p>
 * 回调消息体格式：
 * <pre>
 * {
 *   "appId": "设备ID",
 *   "fromWxid": "发送方微信ID",
 *   "toWxid": "接收方微信ID",
 *   "type": 1,
 *   "content": "消息正文",
 *   "msgId": "消息唯一ID",
 *   "createTime": 1710000000
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WechatApiWebhookController {

    private final WechatApiService wechatApiService;
    private final WechatApiProperties props;

    /**
     * 接收 WechatApi 推送的消息回调
     * <p>
     * 必须在 200ms 内返回 HTTP 200，否则 WechatApi 判定推送失败。
     * 业务处理应异步执行，此处仅做日志记录和快速响应。
     */
    @PostMapping("/wechat")
    public ResponseEntity<Map<String, String>> onMessage(@RequestBody WebhookMessage msg) {
        log.info("[WechatApi Webhook] type={}, from={}, to={}, content={}",
                msg.getType(), msg.getFromWxid(), msg.getToWxid(),
                msg.getContent() != null && msg.getContent().length() > 80
                        ? msg.getContent().substring(0, 80) + "..." : msg.getContent());

        // TODO: 后续在此处分发业务逻辑（定时推送触发、关键词回复、@机器人响应等）
        // 注意：业务逻辑应异步执行，不能阻塞此回调

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Webhook 健康检查（GET 请求，用于验证回调地址可达）
     */
    @GetMapping("/wechat")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "peacehaven"));
    }

    /**
     * WechatApi 回调消息体
     */
    @Data
    public static class WebhookMessage {
        /** 设备 ID */
        private String appId;
        /** 发送方微信 ID（群消息时为 xxx@chatroom） */
        private String fromWxid;
        /** 接收方微信 ID */
        private String toWxid;
        /** 消息类型：1=文本, 3=图片, 43=视频, 49=链接/小程序 */
        private Integer type;
        /** 消息正文 */
        private String content;
        /** 消息唯一 ID */
        private String msgId;
        /** 创建时间（Unix 时间戳） */
        private Long createTime;
    }
}
