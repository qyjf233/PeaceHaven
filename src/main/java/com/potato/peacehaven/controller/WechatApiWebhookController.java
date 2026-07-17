package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.TraceContext;
import com.potato.peacehaven.dto.WechatApiCallbackEvent;
import com.potato.peacehaven.service.WechatApiWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WechatApi Webhook 回调接收端点
 * <p>
 * WechatApi 云端在收到微信消息后，会向此端点 POST JSON 报文。
 * 此端点路径不在 /admin 之下，无需管理员鉴权，由 WechatApi 服务端直连。
 * <p>
 * <b>关键约束</b>：
 * <ul>
 *   <li>必须在 3 秒内返回 HTTP 200，否则网关判定超时放弃推送</li>
 *   <li>业务逻辑通过 CompletableFuture.runAsync 异步执行，不能阻塞此回调</li>
 *   <li>WechatApi 配置回调地址时，会先发一条包含"验证回调地址是否可用"文本的测试消息，本端点正常响应 200 即可通过验证</li>
 * </ul>
 * <p>
 * 回调报文完整结构参见 {@link WechatApiCallbackEvent}，核心字段：
 * <pre>
 * {
 *   "TypeName": "AddMsg",          // 事件类型
 *   "Appid":    "wx_xxx",          // 设备 appId
 *   "Wxid":     "wxid_xxx",        // 当前登录微信号
 *   "Data": {
 *     "MsgType":        1,          // 消息类型
 *     "FromUserName":   {"string": "wxid_sender"},
 *     "ToUserName":     {"string": "wxid_receiver"},
 *     "Content":        {"string": "消息正文"},
 *     "NewMsgId":       123456,     // 用于去重
 *     "CreateTime":     1710000000,
 *     "PushContent":    "..."
 *   }
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WechatApiWebhookController {

    private final WechatApiWebhookService webhookService;

    /**
     * 接收 WechatApi 推送的消息回调（POST JSON）
     * <p>
     * 立即返回空字符串 + HTTP 200，业务处理异步执行。
     * <p>
     * 支持所有 TypeName：AddMsg / ModContacts / DelContacts / Offline / FinderSyncMsg / FinderBypMsg
     * <p>
     * WechatApi 配置回调时会发送验证消息（TypeName=AddMsg, MsgType=1, Content="验证回调地址是否可用"），
     * 此处正常响应 200 即可通过验证。
     */
    @PostMapping("/wechat")
    public ResponseEntity<String> onCallback(@RequestBody WechatApiCallbackEvent event) {
        // 生成链路追踪 ID，贯穿整条消息处理链路
        String traceId = TraceContext.generate();

        log.info("[Webhook] 收到回调 typeName={}, appId={}, traceId={}",
                event.getTypeName(), event.getAppId(), traceId);

        // 异步处理，不阻塞 HTTP 响应
        CompletableFuture.runAsync(() -> {
            TraceContext.set(traceId);
            try {
                webhookService.handleEvent(event);
            } catch (Exception e) {
                log.error("[Webhook] 异步处理异常 traceId={}", traceId, e);
            } finally {
                TraceContext.clear();
            }
        });

        // 立即返回空字符串 + 200（WechatApi 要求快速响应）
        return ResponseEntity.ok("");
    }

    /**
     * Webhook 健康检查（GET 请求）
     * <p>
     * 用于验证回调地址是否可达，或手动检测服务状态。
     */
    @GetMapping("/wechat")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "peacehaven",
                "message", "WechatApi webhook endpoint is alive"
        ));
    }
}
