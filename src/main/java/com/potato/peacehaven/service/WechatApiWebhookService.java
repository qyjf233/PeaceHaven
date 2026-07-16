package com.potato.peacehaven.service;

import com.potato.peacehaven.ai.pipeline.AiReplyPipeline;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.dto.WechatApiCallbackEvent;
import com.potato.peacehaven.entity.BotChatRecord;
import com.potato.peacehaven.entity.BotMessageLog;
import com.potato.peacehaven.repository.BotChatRecordRepository;
import com.potato.peacehaven.repository.BotGroupMemberRepository;
import com.potato.peacehaven.repository.BotMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

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
    private final BotChatRecordRepository chatRecordRepo;
    private final BotGroupMemberRepository groupMemberRepo;
    private final WechatApiProperties props;
    private final AiProperties aiProps;
    private final AiReplyPipeline aiReplyPipeline;
    private final AiWhitelistService aiWhitelistService;

    /** 群名本地缓存（1 小时 TTL，避免每条消息都查 DB） */
    private final RoomNameCache roomNameCache = new RoomNameCache();

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

        // 1. 持久化日志（所有事件类型均记录到 bot_message_log）
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

            // 白名单训练检查
            boolean trainingAllowed = aiWhitelistService.isGroupTrainingAllowed(chatroomId);
            boolean replyAllowed = aiWhitelistService.isGroupReplyAllowed(chatroomId);
            boolean aiReady = aiProps.isReady();
            log.info("[Webhook] 群消息白名单诊断 chatroom={}, training={}, reply={}, aiReady={}",
                    chatroomId, trainingAllowed, replyAllowed, aiReady);

            // 目标群聊文本消息 → 持久化到聊天记录表（用于 RAG 向量化，需训练白名单命中）
            if (trainingAllowed) {
                saveChatRecord(event, pureContent);
            }

            // AI 分身回复流水线（异步执行，不阻塞 webhook，需回复白名单命中）
            if (aiReady
                    && !event.isGroupSelfSent()
                    && replyAllowed) {
                String senderWxid = event.getGroupSenderWxid();
                if (senderWxid == null) senderWxid = event.getFromWxid();
                String senderNick = resolveSenderNick(senderWxid);
                aiReplyPipeline.processGroupMessage(
                        chatroomId, senderWxid, senderNick,
                        pureContent, mentioned);
            }
        } else {
            String senderWxid = event.getFromWxid();
            log.info("[Webhook] 私聊文本消息 from={}, content={}",
                    senderWxid,
                    pureContent != null && pureContent.length() > 100
                            ? pureContent.substring(0, 100) + "..." : pureContent);

            // AI 分身回复私聊（好友回复白名单命中时触发）
            boolean friendReplyAllowed = aiWhitelistService.isFriendReplyAllowed(senderWxid);
            boolean aiReady = aiProps.isReady();
            log.info("[Webhook] 私聊白名单诊断 from={}, friendReply={}, aiReady={}",
                    senderWxid, friendReplyAllowed, aiReady);
            if (aiReady && friendReplyAllowed) {
                aiReplyPipeline.processGroupMessage(
                        null, senderWxid, "",
                        pureContent, false);
            }
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

    // ========================================================================
    //  聊天记录持久化（目标群聊 → bot_chat_record）
    // ========================================================================

    /**
     * 将白名单群聊的文本消息持久化到聊天记录表，用于后续 RAG 向量化训练
     * <p>前置条件：已通过 isGroupTrainingAllowed 白名单检查
     */
    private void saveChatRecord(WechatApiCallbackEvent event, String pureContent) {
        String chatroomId = event.getChatroomId();

        Long newMsgId = event.getNewMsgId();
        String appId = event.getAppId();
        if (newMsgId == null || appId == null) return;

        // 去重
        if (chatRecordRepo.existsByMsgIdAndAppId(newMsgId, appId)) {
            log.debug("[ChatRecord] 已存在，跳过 msgId={}", newMsgId);
            return;
        }

        boolean selfSent = event.isGroupSelfSent();
        // 真实发送者：自己发的群消息 senderWxid 为当前登录 wxid，否则取 Content 切割出的 wxid
        String senderWxid = selfSent ? event.getWxid() : event.getGroupSenderWxid();
        if (senderWxid == null) senderWxid = event.getFromWxid();

        // 解析发送者昵称（优先从群成员表取）
        String senderNick = resolveSenderNick(senderWxid);

        // 解析群名
        String roomName = resolveRoomName(chatroomId, appId);

        BotChatRecord record = BotChatRecord.builder()
                .msgId(newMsgId)
                .appId(appId)
                .roomId(chatroomId)
                .roomName(roomName)
                .senderWxid(senderWxid)
                .senderNick(senderNick)
                .isSelf(selfSent)
                .msgType(event.getMsgType())
                .content(pureContent != null ? truncate(pureContent, 65000) : null)
                .rawContent(event.getContentString())
                .createTime(event.getData() != null ? event.getData().getCreateTime() : null)
                .build();

        try {
            chatRecordRepo.save(record);
            log.info("[ChatRecord] 已存储 roomId={}, sender={}({}), content={}",
                    chatroomId, senderNick, senderWxid,
                    pureContent != null && pureContent.length() > 60
                            ? pureContent.substring(0, 60) + "..." : pureContent);
        } catch (Exception e) {
            log.error("[ChatRecord] 存储失败 msgId={}", newMsgId, e);
        }
    }

    /**
     * 从群成员表解析发送者昵称（displayName > nickName > wxid 兜底）
     */
    private String resolveSenderNick(String wxid) {
        if (wxid == null) return null;
        return groupMemberRepo.findByWxid(wxid)
                .map(m -> m.getEffectiveName())
                .orElse(wxid);
    }

    /**
     * 获取群名（内存缓存，1 小时 TTL）
     * <p>优先从群成员表所在群的群名缓存取，fallback 到 groupId 本身
     */
    private String resolveRoomName(String roomId, String appId) {
        RoomNameCache.Entry entry = roomNameCache.get(roomId);
        if (entry != null) return entry.name;

        // 从群成员表中任取一条记录所在群的群名（BotGroupMember 不直接存群名，此处暂用 roomId 作 fallback）
        // 后续可在 syncGroupMembers 时同步群名到独立表，或从 ModContacts 事件中更新
        String name = roomId;
        roomNameCache.put(roomId, name);
        return name;
    }

    /**
     * 截断文本，超出 maxLen 保留前 maxLen-3 字符并追加 ...
     */
    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    // ========================================================================
    //  内存缓存
    // ========================================================================

    /** 群名本地缓存（ConcurrentHashMap，1h TTL） */
    private static class RoomNameCache {
        private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
        private static final long TTL_MS = 3600_000L; // 1 小时

        Entry get(String key) {
            Entry e = map.get(key);
            if (e != null && !e.isExpired()) return e;
            if (e != null) map.remove(key);
            return null;
        }

        void put(String key, String name) {
            map.put(key, new Entry(name, System.currentTimeMillis()));
        }

        static class Entry {
            final String name;
            final long timestamp;
            Entry(String name, long timestamp) { this.name = name; this.timestamp = timestamp; }
            boolean isExpired() { return System.currentTimeMillis() - timestamp > TTL_MS; }
        }
    }
}
