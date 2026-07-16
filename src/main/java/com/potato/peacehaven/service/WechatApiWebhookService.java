package com.potato.peacehaven.service;

import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.dto.WechatApiCallbackEvent;
import com.potato.peacehaven.entity.BotMessageLog;
import com.potato.peacehaven.repository.BotMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * WechatApi Webhook 事件处理服务
 * <p>
 * 由 {@link WechatApiWebhookController} 异步调用，负责：
 * <ol>
 *   <li>去重检查（Appid + NewMsgId）</li>
 *   <li>持久化消息日志</li>
 *   <li>按 TypeName 分发到具体处理器</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatApiWebhookService {

    private final BotMessageLogRepository messageLogRepo;
    private final WechatApiProperties props;

    /**
     * 主入口：接收解析好的回调事件，执行去重 + 持久化 + 分发
     */
    public void handleEvent(WechatApiCallbackEvent event) {
        if (event == null || event.getTypeName() == null) {
            log.warn("[Webhook] 收到空事件或无 TypeName，忽略");
            return;
        }

        String typeName = event.getTypeName();
        log.info("[Webhook] 收到事件 typeName={}, appId={}, wxid={}",
                typeName, event.getAppId(), event.getWxid());

        // 1. 持久化日志（所有事件类型均记录）
        persistLog(event);

        // 2. 去重检查（仅 AddMsg 有 NewMsgId，其他类型不重复）
        if ("AddMsg".equals(typeName)) {
            Long newMsgId = event.getNewMsgId();
            if (newMsgId != null && event.getAppId() != null) {
                if (messageLogRepo.existsByNewMsgIdAndAppId(newMsgId, event.getAppId())) {
                    log.debug("[Webhook] 重复消息 newMsgId={}，跳过", newMsgId);
                    return;
                }
            }
        }

        // 3. 按 TypeName 分发
        try {
            switch (typeName) {
                case "AddMsg"        -> handleAddMsg(event);
                case "ModContacts"   -> handleModContacts(event);
                case "DelContacts"   -> handleDelContacts(event);
                case "Offline"       -> handleOffline(event);
                case "FinderSyncMsg" -> log.info("[Webhook] 视频号互动通知 appId={}", event.getAppId());
                case "FinderBypMsg"  -> log.info("[Webhook] 视频号私信通知 appId={}", event.getAppId());
                default              -> log.info("[Webhook] 未知事件类型 typeName={}", typeName);
            }
        } catch (Exception e) {
            log.error("[Webhook] 处理事件异常 typeName={}", typeName, e);
        }
    }

    // ========================================================================
    //  AddMsg 分发
    // ========================================================================

    private void handleAddMsg(WechatApiCallbackEvent event) {
        Integer msgType = event.getMsgType();
        if (msgType == null) {
            log.warn("[Webhook] AddMsg 无 MsgType，appId={}", event.getAppId());
            return;
        }

        String from = event.getFromWxid();
        boolean isGroup = event.isGroupMessage();
        String groupSender = event.getGroupSenderWxid();
        String pureContent = event.getPureContent();

        log.info("[Webhook] AddMsg msgType={}, isGroup={}, from={}, groupSender={}, pushContent={}",
                msgType, isGroup, from, groupSender,
                event.getData().getPushContent() != null
                        ? event.getData().getPushContent().getString() : null);

        switch (msgType) {
            case 1     -> handleTextMessage(event, pureContent, isGroup, groupSender);
            case 3     -> log.debug("[Webhook] 图片消息 from={}", from);
            case 34    -> log.debug("[Webhook] 语音消息 from={}", from);
            case 37    -> handleFriendRequest(event);
            case 42    -> log.debug("[Webhook] 名片消息 from={}", from);
            case 43    -> log.debug("[Webhook] 视频消息 from={}", from);
            case 47    -> log.debug("[Webhook] Emoji 消息 from={}", from);
            case 48    -> log.debug("[Webhook] 位置消息 from={}", from);
            case 49    -> handleAppMsg(event);
            case 10000 -> handleSystemNotice(event);
            case 10002 -> handleXmlSystem(event);
            default    -> log.debug("[Webhook] 未处理 MsgType={}", msgType);
        }
    }

    /**
     * 文本消息处理
     * <p>后续可在此扩展：@机器人响应、关键词触发、定时推送等
     */
    private void handleTextMessage(WechatApiCallbackEvent event, String pureContent,
                                   boolean isGroup, String groupSender) {
        if (isGroup) {
            String chatroomId = event.getChatroomId();
            // 判断是否 @机器人（通过昵称匹配，后续可从配置读取机器人昵称）
            boolean mentioned = pureContent != null && pureContent.contains("@");
            log.info("[Webhook] 群文本消息 chatroom={}, sender={}, content={}, mentioned={}",
                    chatroomId, groupSender,
                    pureContent != null && pureContent.length() > 100
                            ? pureContent.substring(0, 100) + "..." : pureContent,
                    mentioned);

            // TODO: @机器人自动响应 / 关键词触发 / 定时推送拦截
        } else {
            log.info("[Webhook] 私聊文本消息 from={}, content={}",
                    event.getFromWxid(),
                    pureContent != null && pureContent.length() > 100
                            ? pureContent.substring(0, 100) + "..." : pureContent);

            // TODO: 私聊自动回复
        }
    }

    /**
     * 好友请求（MsgType=37）
     * <p>Content.string 包含申请人信息 XML，可用于自动同意好友
     */
    private void handleFriendRequest(WechatApiCallbackEvent event) {
        log.info("[Webhook] 好友请求 from={}, pushContent={}",
                event.getFromWxid(),
                event.getData().getPushContent() != null
                        ? event.getData().getPushContent().getString() : null);
        // TODO: 自动同意好友（需在线3天后才可调用 addContacts 接口）
    }

    /**
     * 复合消息（MsgType=49）
     * <p>需解析 Content.string 中 XML 的 msg.appmsg.type：
     * <ul>
     *   <li>5  = 公众号/文章链接（也可能是群邀请链接）</li>
     *   <li>6  = 文件已发送完成（可下载/转发）</li>
     *   <li>33/36 = 小程序卡片</li>
     *   <li>57 = 引用回复消息</li>
     *   <li>74 = 文件发送中（仅提示，不可下载）</li>
     *   <li>2000 = 微信转账（仅提醒）</li>
     *   <li>2001 = 微信红包（仅提醒）</li>
     * </ul>
     */
    private void handleAppMsg(WechatApiCallbackEvent event) {
        String content = event.getContentString();
        String pushContent = event.getData().getPushContent() != null
                ? event.getData().getPushContent().getString() : null;

        // 简易解析 appmsg.type（完整解析需 XML parser，此处用正则快速提取）
        String appMsgType = extractAppMsgType(content);
        log.info("[Webhook] 复合消息 MsgType=49, appmsg.type={}, from={}, pushContent={}",
                appMsgType, event.getFromWxid(), pushContent);

        // TODO: 按 appmsg.type 分发具体业务逻辑
    }

    /**
     * 系统通知（MsgType=10000）
     * <p>Content.string 为纯文本，包含：被移出群聊 / 修改群名 / 成为新群主 等
     */
    private void handleSystemNotice(WechatApiCallbackEvent event) {
        String content = event.getContentString();
        String from = event.getFromWxid();
        log.info("[Webhook] 系统通知 from={}, content={}",
                from, content != null && content.length() > 200 ? content.substring(0, 200) : content);

        if (content != null) {
            if (content.contains("移出群聊")) {
                log.warn("[Webhook] 机器人被踢出群聊 chatroom={}", from);
                // TODO: 告警通知管理员
            } else if (content.contains("修改群名")) {
                log.info("[Webhook] 群名变更 chatroom={}", from);
            } else if (content.contains("新群主")) {
                log.info("[Webhook] 群主变更 chatroom={}", from);
            }
        }
    }

    /**
     * XML 系统消息（MsgType=10002）
     * <p>需解析 Content.string 中 XML 的 sysmsg.type：
     * <ul>
     *   <li>revokemsg = 消息撤回</li>
     *   <li>pat = 拍一拍</li>
     *   <li>sysmsgtemplate = 踢出群聊 / 解散群聊</li>
     *   <li>mmchatroombarannouncememt = 群公告更新</li>
     *   <li>roomtoolstips = 群待办</li>
     * </ul>
     */
    private void handleXmlSystem(WechatApiCallbackEvent event) {
        String content = event.getContentString();
        String from = event.getFromWxid();

        String sysType = extractSysMsgType(content);
        log.info("[Webhook] XML系统消息 MsgType=10002, sysmsg.type={}, from={}", sysType, from);

        if (sysType != null) {
            switch (sysType) {
                case "revokemsg"  -> log.debug("[Webhook] 消息撤回 from={}", from);
                case "pat"        -> log.debug("[Webhook] 拍一拍 from={}", from);
                case "sysmsgtemplate" -> {
                    // 可能是踢出群聊或解散群聊，解析 template 内容判断
                    if (content != null && content.contains("解散")) {
                        log.warn("[Webhook] 群聊解散 chatroom={}", from);
                    } else if (content != null && content.contains("移出")) {
                        log.info("[Webhook] 踢出群成员 chatroom={}", from);
                    }
                }
                case "mmchatroombarannouncememt" -> log.info("[Webhook] 群公告更新 chatroom={}", from);
                case "roomtoolstips" -> log.debug("[Webhook] 群待办 chatroom={}", from);
                default -> log.debug("[Webhook] 未处理 sysmsg.type={}", sysType);
            }
        }
    }

    // ========================================================================
    //  ModContacts / DelContacts / Offline
    // ========================================================================

    /**
     * 联系人/群信息变更
     * <p>Data.UserName.string 为变更对象的 wxid 或 @chatroom
     * <p>Data 包含 NickName/BigHeadImgUrl/ChatRoomOwner 等字段
     */
    private void handleModContacts(WechatApiCallbackEvent event) {
        String userName = event.getData() != null && event.getData().getUserName() != null
                ? event.getData().getUserName().getString() : null;
        String nickName = event.getData() != null && event.getData().getNickName() != null
                ? event.getData().getNickName().getString() : null;
        boolean isGroup = userName != null && userName.endsWith("@chatroom");

        log.info("[Webhook] 联系人变更 isGroup={}, userName={}, nickName={}", isGroup, userName, nickName);

        if (isGroup) {
            // 群信息变更（群名/群主等）
            String chatRoomOwner = event.getData().getChatRoomOwner();
            log.info("[Webhook] 群信息变更 chatroom={}, owner={}", userName, chatRoomOwner);
        } else {
            // 好友资料变更 / 新好友通过验证
            log.info("[Webhook] 好友资料变更 wxid={}, nickName={}", userName, nickName);
        }
    }

    /**
     * 删除好友 / 退出群聊
     * <p>Data.UserName.string 为被删除好友 wxid 或退出的群 @chatroom
     * <p>Data.DeleteContactScen 为删除场景
     */
    private void handleDelContacts(WechatApiCallbackEvent event) {
        String userName = event.getData() != null && event.getData().getUserName() != null
                ? event.getData().getUserName().getString() : null;
        Integer scene = event.getData() != null ? event.getData().getDeleteContactScen() : null;
        boolean isGroup = userName != null && userName.endsWith("@chatroom");

        if (isGroup) {
            log.info("[Webhook] 退出群聊 chatroom={}, scene={}", userName, scene);
        } else {
            log.info("[Webhook] 删除好友 wxid={}, scene={}", userName, scene);
        }
    }

    /**
     * 节点掉线告警
     * <p>需要立即通知管理员并标记设备离线状态
     */
    private void handleOffline(WechatApiCallbackEvent event) {
        log.warn("[Webhook] ⚠️ 设备掉线！appId={}, wxid={}", event.getAppId(), event.getWxid());
        // TODO: 触发告警通知（邮件 / 钉钉 / 备用联系渠道）
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    /**
     * 持久化消息日志
     */
    private void persistLog(WechatApiCallbackEvent event) {
        try {
            String content = event.getContentString();
            if (content != null && content.length() > 2000) {
                content = content.substring(0, 2000);
            }

            String pushContent = null;
            if (event.getData() != null && event.getData().getPushContent() != null) {
                pushContent = event.getData().getPushContent().getString();
            }

            BotMessageLog logEntry = BotMessageLog.builder()
                    .typeName(event.getTypeName())
                    .msgType(event.getMsgType())
                    .appId(event.getAppId())
                    .wxid(event.getWxid())
                    .fromWxid(event.getFromWxid())
                    .toWxid(event.getToWxid())
                    .content(content)
                    .pushContent(pushContent)
                    .newMsgId(event.getNewMsgId())
                    .isGroup(event.isGroupMessage())
                    .groupSenderWxid(event.getGroupSenderWxid())
                    .chatroomId(event.getChatroomId())
                    .wxCreateTime(event.getData() != null ? event.getData().getCreateTime() : null)
                    .build();
            messageLogRepo.save(logEntry);
        } catch (Exception e) {
            log.error("[Webhook] 持久化消息日志失败", e);
        }
    }

    /**
     * 从 Content XML 中快速提取 appmsg.type（简易正则，避免完整 XML 解析开销）
     */
    private String extractAppMsgType(String content) {
        if (content == null) return null;
        int idx = content.indexOf("<type>");
        if (idx < 0) return null;
        int end = content.indexOf("</type>", idx);
        if (end < 0) return null;
        return content.substring(idx + 6, end).trim();
    }

    /**
     * 从 Content XML 中快速提取 sysmsg.type
     */
    private String extractSysMsgType(String content) {
        if (content == null) return null;
        // sysmsg 格式：<sysmsg type="xxx">
        int idx = content.indexOf("type=\"");
        if (idx < 0) return null;
        int end = content.indexOf("\"", idx + 6);
        if (end < 0) return null;
        return content.substring(idx + 6, end);
    }
}
