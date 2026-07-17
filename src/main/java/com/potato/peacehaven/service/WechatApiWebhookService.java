package com.potato.peacehaven.service;

import com.potato.peacehaven.ai.pipeline.AiReplyPipeline;
import com.potato.peacehaven.ai.pipeline.AiReplyTracker;
import com.potato.peacehaven.ai.pipeline.PrivateMessageBuffer;
import com.potato.peacehaven.config.AiProperties;
import com.potato.peacehaven.config.TraceContext;
import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.dto.WechatApiCallbackEvent;
import com.potato.peacehaven.entity.BotChatRecord;
import com.potato.peacehaven.entity.BotEmojiLibrary;
import com.potato.peacehaven.entity.BotMessageLog;
import com.potato.peacehaven.repository.BotChatRecordRepository;
import com.potato.peacehaven.repository.BotEmojiLibraryRepository;
import com.potato.peacehaven.repository.BotGroupMemberRepository;
import com.potato.peacehaven.repository.BotMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.PageRequest;

import java.util.List;
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
    private final BotEmojiLibraryRepository emojiLibraryRepo;
    private final WechatApiProperties props;
    private final AiProperties aiProps;
    private final AiReplyPipeline aiReplyPipeline;
    private final AiWhitelistService aiWhitelistService;
    private final AiReplyTracker aiReplyTracker;
    private final PrivateMessageBuffer privateMessageBuffer;

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

        // 1. 去重检查（仅 AddMsg 有 NewMsgId，必须在 persistLog 之前，否则刚插入的记录会被误判为重复）
        if ("AddMsg".equals(typeName)) {
            Long newMsgId = event.getNewMsgId();
            if (newMsgId != null && event.getAppId() != null) {
                if (messageLogRepo.existsByNewMsgIdAndAppId(newMsgId, event.getAppId())) {
                    log.debug("[Webhook] 重复消息 newMsgId={}，跳过", newMsgId);
                    return;
                }
            }
        }

        // 2. 持久化日志（仅白名单+训练开启的消息落库 bot_message_log）
        persistLog(event);

        // 3. 按 TypeName 分发
        try {
            switch (typeName) {
                case "AddMsg"        -> handleAddMsg(event);
                case "ModContacts"   -> handleModContacts(event);
                case "DelContacts"   -> handleDelContacts(event);
                case "Offline"       -> handleOffline(event);
                case "FinderSyncMsg" -> log.debug("[Webhook] 视频号互动通知 appId={}", event.getAppId());
                case "FinderBypMsg"  -> log.debug("[Webhook] 视频号私信通知 appId={}", event.getAppId());
                default              -> log.debug("[Webhook] 未知事件类型 typeName={}", typeName);
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

        log.debug("[Webhook] AddMsg msgType={}, isGroup={}, from={}, groupSender={}, pushContent={}",
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
            case 47    -> handleEmojiMessage(event);
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
            log.debug("[Webhook] 群文本消息 chatroom={}, sender={}, content={}, mentioned={}",
                    chatroomId, groupSender,
                    pureContent != null && pureContent.length() > 100
                            ? pureContent.substring(0, 100) + "..." : pureContent,
                    mentioned);

            // TODO: @机器人自动响应 / 关键词触发 / 定时推送拦截

            // 白名单训练检查
            boolean trainingAllowed = aiWhitelistService.isGroupTrainingAllowed(chatroomId);
            boolean replyAllowed = aiWhitelistService.isGroupReplyAllowed(chatroomId);
            boolean aiReady = aiProps.isReady();
            log.debug("[Webhook] 群消息白名单诊断 chatroom={}, training={}, reply={}, aiReady={}",
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
                String traceId = TraceContext.get();
                log.info("[Webhook] 触发 AI Pipeline chatroom={}, sender={}, traceId={}",
                        chatroomId, senderNick, traceId);
                aiReplyPipeline.processGroupMessage(
                        chatroomId, senderWxid, senderNick,
                        pureContent, mentioned, traceId);
            }
        } else {
            String senderWxid = event.getFromWxid();
            log.debug("[Webhook] 私聊文本消息 from={}, content={}",
                    senderWxid,
                    pureContent != null && pureContent.length() > 100
                            ? pureContent.substring(0, 100) + "..." : pureContent);

            // AI 分身回复私聊（好友回复白名单命中时触发）
            boolean friendReplyAllowed = aiWhitelistService.isFriendReplyAllowed(senderWxid);
            boolean aiReady = aiProps.isReady();
            log.debug("[Webhook] 私聊白名单诊断 from={}, friendReply={}, aiReady={}",
                    senderWxid, friendReplyAllowed, aiReady);
            if (aiReady && friendReplyAllowed) {
                // 通过消息聚合器处理（等待对方发完再回复，避免抢话）
                String traceId = TraceContext.get();
                log.info("[Webhook] 触发 AI Pipeline(私聊) from={}, traceId={}", senderWxid, traceId);
                privateMessageBuffer.accept(senderWxid, "", pureContent, traceId);
            }
        }
    }

    /**
     * 好友请求（MsgType=37）
     * <p>Content.string 包含申请人信息 XML，可用于自动同意好友
     */
    private void handleFriendRequest(WechatApiCallbackEvent event) {
        log.debug("[Webhook] 好友请求 from={}, pushContent={}",
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
        boolean isGroup = event.isGroupMessage();
        log.debug("[Webhook] 复合消息 MsgType=49, appmsg.type={}, isGroup={}, from={}, pushContent={}",
                appMsgType, isGroup, event.getFromWxid(), pushContent);

        // 57 = 引用回复消息 → 作为文本消息处理（带引用上下文）
        if ("57".equals(appMsgType) && isGroup && !event.isGroupSelfSent()) {
            handleQuotedReply(event, content);
        }
    }

    /**
     * 引用回复消息处理（appmsg.type=57）
     * <p>
     * XML 格式：
     * <pre>
     * &lt;msg&gt;&lt;appmsg&gt;
     *   &lt;title&gt;回复的新消息&lt;/title&gt;
     *   &lt;type&gt;57&lt;/type&gt;
     *   &lt;refermsg&gt;
     *     &lt;type&gt;1&lt;/type&gt;
     *     &lt;content&gt;被引用的原消息&lt;/content&gt;
     *     &lt;displayname&gt;原发送者&lt;/displayname&gt;
     *   &lt;/refermsg&gt;
     * &lt;/appmsg&gt;&lt;/msg&gt;
     * </pre>
     * </p>
     */
    private void handleQuotedReply(WechatApiCallbackEvent event, String rawContent) {
        // 群消息 Content 格式: "wxid_xxx:\n<msg>..."
        String xml = rawContent;
        if (event.isGroupMessage() && !event.isGroupSelfSent()) {
            int sep = rawContent.indexOf(":\n");
            if (sep > 0) {
                xml = rawContent.substring(sep + 2);
            }
        }

        // 解析引用回复内容
        String replyText = extractXmlTag(xml, "title");
        String quotedText = extractRefermsgContent(xml);
        String quotedSender = extractXmlTag(xml, "displayname");

        if (replyText == null || replyText.isBlank()) {
            log.debug("[Webhook] 引用回复 title 为空，跳过");
            return;
        }

        // 组合成带引用上下文的消息：回复内容 + [引用 xxx: 原消息]
        String combinedContent;
        if (quotedText != null && !quotedText.isBlank()) {
            String senderLabel = (quotedSender != null && !quotedSender.isBlank()) ? quotedSender : "对方";
            combinedContent = replyText + " [引用 " + senderLabel + ": " + quotedText + "]";
        } else {
            combinedContent = replyText;
        }

        String chatroomId = event.getChatroomId();
        String senderWxid = event.getGroupSenderWxid();
        if (senderWxid == null) senderWxid = event.getFromWxid();
        String senderNick = resolveSenderNick(senderWxid);

        log.info("[Webhook] 引用回复 chatroom={}, sender={}, reply={}, quoted={}",
                chatroomId, senderNick,
                replyText.length() > 50 ? replyText.substring(0, 50) + "..." : replyText,
                quotedText != null && quotedText.length() > 50 ? quotedText.substring(0, 50) + "..." : quotedText);

        // 白名单检查
        boolean trainingAllowed = aiWhitelistService.isGroupTrainingAllowed(chatroomId);
        boolean replyAllowed = aiWhitelistService.isGroupReplyAllowed(chatroomId);
        boolean aiReady = aiProps.isReady();

        // 训练：持久化聊天记录（用纯回复文本，不含引用标记）
        if (trainingAllowed) {
            saveChatRecord(event, replyText);
        }

        // AI 回复：使用组合内容（带引用上下文，让 AI 知道对方在引用什么）
        if (aiReady && replyAllowed) {
            boolean mentioned = replyText.contains("@");
            String traceId = TraceContext.get();
            log.info("[Webhook] 触发 AI Pipeline(引用回复) chatroom={}, sender={}, traceId={}",
                    chatroomId, senderNick, traceId);
            aiReplyPipeline.processGroupMessage(
                    chatroomId, senderWxid, senderNick,
                    combinedContent, mentioned, traceId);
        }
    }

    /**
     * 从 XML 中提取指定标签的文本内容（轻量级，不引入 XML parser）
     */
    private String extractXmlTag(String xml, String tagName) {
        if (xml == null) return null;
        String open = "<" + tagName + ">";
        String close = "</" + tagName + ">";
        int start = xml.indexOf(open);
        if (start < 0) return null;
        start += open.length();
        int end = xml.indexOf(close, start);
        if (end < 0) return null;
        String value = xml.substring(start, end).trim();
        // 处理 CDATA
        if (value.startsWith("<![CDATA[")) {
            value = value.substring(9);
            if (value.endsWith("]]>")) value = value.substring(0, value.length() - 3);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * 从引用回复 XML 中提取 refermsg.content
     * <p>refermsg 是嵌套在 appmsg 内部的，需要先定位 refermsg 区域</p>
     */
    private String extractRefermsgContent(String xml) {
        if (xml == null) return null;
        int refStart = xml.indexOf("<refermsg>");
        if (refStart < 0) return null;
        int refEnd = xml.indexOf("</refermsg>", refStart);
        if (refEnd < 0) return null;
        String refBlock = xml.substring(refStart, refEnd);
        return extractXmlTag(refBlock, "content");
    }

    /**
     * 表情包消息处理（MsgType=47）
     * <p>
     * XML 格式：
     * <pre>
     * &lt;msg&gt;
     *   &lt;emoji md5="xxx" type="2" len="14732" productid="..." width="240" height="240"/&gt;
     *   &lt;gameext type="0" content="0"/&gt;
     * &lt;/msg&gt;
     * </pre>
     * </p>
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>提取 emoji 的 MD5、文件大小等关键信息</li>
     *   <li>入库 / 更新 bot_emoji_library（已存在则 usageCount++）</li>
     *   <li>采集上下文样本（最近 3 条前后消息）</li>
     * </ol>
     * </p>
     */
    private void handleEmojiMessage(WechatApiCallbackEvent event) {
        String rawContent = event.getContentString();
        if (rawContent == null) {
            log.debug("[Webhook] Emoji 消息 content 为空，跳过");
            return;
        }

        // 群消息 Content 格式: "wxid_xxx:\n<msg>..."
        String xml = rawContent;
        if (event.isGroupMessage() && !event.isGroupSelfSent()) {
            int sep = rawContent.indexOf(":\n");
            if (sep > 0) xml = rawContent.substring(sep + 2);
        }

        // 提取 emoji 属性
        String emojiMd5 = extractXmlAttribute(xml, "emoji", "md5");
        String lenStr = extractXmlAttribute(xml, "emoji", "len");
        String typeStr = extractXmlAttribute(xml, "emoji", "type");
        String widthStr = extractXmlAttribute(xml, "emoji", "width");
        String heightStr = extractXmlAttribute(xml, "emoji", "height");
        String productId = extractXmlAttribute(xml, "emoji", "productid");

        if (emojiMd5 == null || emojiMd5.isBlank()) {
            log.debug("[Webhook] Emoji 消息 MD5 为空，跳过");
            return;
        }

        int emojiSize = parseIntSafe(lenStr, 0);
        Integer emojiType = parseIntSafe(typeStr, null);
        Integer width = parseIntSafe(widthStr, null);
        Integer height = parseIntSafe(heightStr, null);

        // 确定发送者
        String senderWxid;
        if (event.isGroupMessage()) {
            senderWxid = event.getGroupSenderWxid();
            if (senderWxid == null) senderWxid = event.getFromWxid();
        } else {
            senderWxid = event.getFromWxid();
        }
        String senderNick = resolveSenderNick(senderWxid);

        log.info("[Webhook] 表情包消息 from={}({}), md5={}, size={}, type={}",
                senderNick, senderWxid, emojiMd5, emojiSize, emojiType);

        // 入库 / 更新 + 上下文样本采集
        BotEmojiLibrary emoji = null;
        try {
            emoji = emojiLibraryRepo.findByMd5(emojiMd5).orElse(null);

            // 采集上下文样本（最近 3 条文本消息）
            String contextSample = collectEmojiContext(event, senderNick);

            if (emoji == null) {
                // 新表情包入库
                String samples = (contextSample != null)
                        ? "[" + contextSample + "]" : "[]";
                emoji = BotEmojiLibrary.builder()
                        .md5(emojiMd5)
                        .emojiSize(emojiSize)
                        .emojiType(emojiType)
                        .width(width)
                        .height(height)
                        .productId(productId)
                        .usageCount(1)
                        .contextSamples(samples)
                        .labeled(false)
                        .build();
                emojiLibraryRepo.save(emoji);
                log.info("[EmojiLibrary] 新表情包入库 md5={}, size={}", emojiMd5, emojiSize);
            } else {
                // 已存在：使用次数 +1，追加上下文样本
                emoji.setUsageCount(emoji.getUsageCount() + 1);
                appendContextSample(emoji, contextSample);
                emojiLibraryRepo.save(emoji);
                log.debug("[EmojiLibrary] 表情包使用次数+1 md5={}, count={}",
                        emojiMd5, emoji.getUsageCount());
            }
        } catch (Exception e) {
            log.error("[EmojiLibrary] 表情包入库失败 md5={}", emojiMd5, e);
            return;
        }

        // ── AI 回复决策 ──
        // 已标注 → 注入描述到 prompt，触发 AI Pipeline
        // 未标注 → 静默采集，不调 LLM（零成本）
        if (emoji != null && Boolean.TRUE.equals(emoji.getLabeled())
                && emoji.getDescription() != null && !emoji.getDescription().isBlank()) {

            if (!event.isGroupMessage() || event.isGroupSelfSent()) return;

            String chatroomId = event.getChatroomId();
            boolean replyAllowed = aiWhitelistService.isGroupReplyAllowed(chatroomId);
            boolean aiReady = aiProps.isReady();

            if (aiReady && replyAllowed) {
                // 构造带语义描述的消息内容（替代原始 XML）
                String emojiContent = "[对方发了一个表情包：" + emoji.getDescription() + "]";
                String traceId = TraceContext.get();
                log.info("[Webhook] 触发 AI Pipeline(表情包) chatroom={}, sender={}, desc={}, traceId={}",
                        chatroomId, senderNick, emoji.getDescription(), traceId);
                aiReplyPipeline.processGroupMessage(
                        chatroomId, senderWxid, senderNick,
                        emojiContent, false, traceId);
            }
        } else {
            log.debug("[Webhook] 表情包未标注，静默采集 md5={}", emojiMd5);
        }
    }

    /**
     * 采集表情包上下文样本（最近 3 条文本消息）
     */
    private String collectEmojiContext(WechatApiCallbackEvent event, String senderNick) {
        try {
            if (!event.isGroupMessage()) return null;
            String chatroomId = event.getChatroomId();
            Long createTime = event.getData() != null ? event.getData().getCreateTime() : null;
            if (chatroomId == null || createTime == null) return null;

            // 查最近 3 条文本消息（在表情包之前）
            List<BotChatRecord> recentMsgs = chatRecordRepo.findRecentTextBefore(
                    chatroomId, createTime, PageRequest.of(0, 3));

            if (recentMsgs.isEmpty()) return null;

            // 反转使时间正序（查询是 DESC，需要 ASC）
            StringBuilder sb = new StringBuilder(200);
            sb.append("{\"sender\":\"").append(escapeJson(senderNick)).append("\",");
            sb.append("\"before\":[");
            boolean first = true;
            // 从后往前遍历（反转为时间正序）
            for (int i = recentMsgs.size() - 1; i >= 0; i--) {
                BotChatRecord r = recentMsgs.get(i);
                if (!first) sb.append(",");
                first = false;
                String nick = r.getSenderNick() != null ? r.getSenderNick() : r.getSenderWxid();
                String content = r.getContent();
                if (content != null && content.length() > 80) content = content.substring(0, 80) + "...";
                sb.append("\"").append(escapeJson(nick)).append(": ").append(escapeJson(content)).append("\"");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            log.debug("[EmojiLibrary] 上下文采集失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 追加上下文样本到表情包记录（最多保留 10 条，FIFO 淘汰旧的）
     */
    private void appendContextSample(BotEmojiLibrary emoji, String newSample) {
        if (newSample == null) return;

        String existing = emoji.getContextSamples();
        if (existing == null || existing.isBlank() || "[]".equals(existing)) {
            emoji.setContextSamples("[" + newSample + "]");
            return;
        }

        // 简易 JSON 数组追加（去掉尾部 ]，追加新样本，加回 ]）
        String trimmed = existing.trim();
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        // 计算当前样本数，超过 10 条时 FIFO 淘汰最旧的
        int sampleCount = countJsonArrayElements(trimmed);
        if (sampleCount >= 10) {
            // 找到第一个 },{ 的位置，删除第一个元素
            int firstEnd = trimmed.indexOf("},{", 1);
            if (firstEnd > 0) {
                trimmed = trimmed.substring(0, 1) + trimmed.substring(firstEnd + 1);
            }
        }

        emoji.setContextSamples(trimmed + "," + newSample + "]");
    }

    /**
     * 简易计算 JSON 数组元素数（按 },{ 分隔符计数）
     */
    private int countJsonArrayElements(String jsonArrayWithoutClosingBracket) {
        if (jsonArrayWithoutClosingBracket == null || jsonArrayWithoutClosingBracket.length() <= 1) return 0;
        // 内容以 [ 开头，元素之间用 },{ 分隔
        int count = 1;
        int idx = 0;
        while ((idx = jsonArrayWithoutClosingBracket.indexOf("},{", idx)) >= 0) {
            count++;
            idx += 3;
        }
        return count;
    }

    /**
     * 简易 JSON 字符串转义
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 从 XML 中提取指定标签的属性值（轻量级，不引入 XML parser）
     * <p>
     * 示例：{@code extractXmlAttribute(xml, "emoji", "md5")}
     * 从 {@code <emoji md5="xxx" .../>} 中提取 "xxx"
     * </p>
     */
    private String extractXmlAttribute(String xml, String tagName, String attrName) {
        if (xml == null) return null;
        // 定位标签 "<tagName"
        String tagPrefix = "<" + tagName;
        int tagStart = xml.indexOf(tagPrefix);
        if (tagStart < 0) return null;
        // 找到标签结束 ">"（自闭合 /> 或 >）
        int tagEnd = xml.indexOf(">", tagStart);
        if (tagEnd < 0) return null;
        String tagContent = xml.substring(tagStart, tagEnd + 1);
        // 在标签内查找属性 attrName="value"
        String attrPrefix = attrName + "=\"";
        int attrStart = tagContent.indexOf(attrPrefix);
        if (attrStart < 0) return null;
        attrStart += attrPrefix.length();
        int attrEnd = tagContent.indexOf("\"", attrStart);
        if (attrEnd < 0) return null;
        return tagContent.substring(attrStart, attrEnd);
    }

    /**
     * 安全解析整数，失败返回 fallback
     */
    private Integer parseIntSafe(String str, Integer fallback) {
        if (str == null || str.isBlank()) return fallback;
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 系统通知（MsgType=10000）
     * <p>Content.string 为纯文本，包含：被移出群聊 / 修改群名 / 成为新群主 等
     */
    private void handleSystemNotice(WechatApiCallbackEvent event) {
        String content = event.getContentString();
        String from = event.getFromWxid();
        log.debug("[Webhook] 系统通知 from={}, content={}",
                from, content != null && content.length() > 200 ? content.substring(0, 200) : content);

        if (content != null) {
            if (content.contains("移出群聊")) {
                log.warn("[Webhook] 机器人被踢出群聊 chatroom={}", from);
                // TODO: 告警通知管理员
            } else if (content.contains("修改群名")) {
                log.debug("[Webhook] 群名变更 chatroom={}", from);
            } else if (content.contains("新群主")) {
                log.debug("[Webhook] 群主变更 chatroom={}", from);
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
        log.debug("[Webhook] XML系统消息 MsgType=10002, sysmsg.type={}, from={}", sysType, from);

        if (sysType != null) {
            switch (sysType) {
                case "revokemsg"  -> log.debug("[Webhook] 消息撤回 from={}", from);
                case "pat"        -> log.debug("[Webhook] 拍一拍 from={}", from);
                case "sysmsgtemplate" -> {
                    // 可能是踢出群聊或解散群聊，解析 template 内容判断
                    if (content != null && content.contains("解散")) {
                        log.warn("[Webhook] 群聊解散 chatroom={}", from);
                    } else if (content != null && content.contains("移出")) {
                        log.debug("[Webhook] 踢出群成员 chatroom={}", from);
                    }
                }
                case "mmchatroombarannouncememt" -> log.debug("[Webhook] 群公告更新 chatroom={}", from);
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

        log.debug("[Webhook] 联系人变更 isGroup={}, userName={}, nickName={}", isGroup, userName, nickName);

        if (isGroup) {
            // 群信息变更（群名/群主等）— 将群名写入缓存
            String chatRoomOwner = event.getData().getChatRoomOwner();
            if (nickName != null && userName != null) {
                roomNameCache.put(userName, nickName);
                log.debug("[Webhook] 群名已缓存 chatroom={}, name={}", userName, nickName);
            }
            log.debug("[Webhook] 群信息变更 chatroom={}, owner={}", userName, chatRoomOwner);
        } else {
            // 好友资料变更 / 新好友通过验证
            log.debug("[Webhook] 好友资料变更 wxid={}, nickName={}", userName, nickName);
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
            log.debug("[Webhook] 退出群聊 chatroom={}, scene={}", userName, scene);
        } else {
            log.debug("[Webhook] 删除好友 wxid={}, scene={}", userName, scene);
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
     * 持久化消息日志（仅白名单 + 训练开启时才落库 bot_message_log）
     */
    private void persistLog(WechatApiCallbackEvent event) {
        try {
            // ── 白名单训练过滤 ──
            boolean shouldLog = false;
            String filterReason = "";

            if (event.isGroupMessage()) {
                String chatroomId = event.getChatroomId();
                if (chatroomId != null && aiWhitelistService.isGroupTrainingAllowed(chatroomId)) {
                    shouldLog = true;
                    filterReason = "群训练白名单命中: " + chatroomId;
                } else {
                    filterReason = "群不在训练白名单: " + chatroomId;
                }
            } else {
                String senderWxid = event.getFromWxid();
                // 私聊：目前无私聊训练概念，仅回复白名单好友消息才记录
                if (senderWxid != null && aiWhitelistService.isFriendReplyAllowed(senderWxid)) {
                    shouldLog = true;
                    filterReason = "好友回复白名单命中: " + senderWxid;
                } else {
                    filterReason = "好友不在回复白名单: " + senderWxid;
                }
            }

            if (!shouldLog) {
                log.debug("[Webhook] persistLog 跳过: {}", filterReason);
                return;
            }

            log.debug("[Webhook] persistLog 落库: {}, msgType={}", filterReason, event.getMsgType());

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

        // 检测是否为 AI 分身发送的回复（防止 AI 风格回流到 RAG 训练数据）
        boolean isBotReply = selfSent && aiReplyTracker.isAiReply(pureContent);

        BotChatRecord record = BotChatRecord.builder()
                .msgId(newMsgId)
                .appId(appId)
                .roomId(chatroomId)
                .roomName(roomName)
                .senderWxid(senderWxid)
                .senderNick(senderNick)
                .isSelf(selfSent)
                .isBotReply(isBotReply)
                .msgType(event.getMsgType())
                .content(pureContent != null ? truncate(pureContent, 65000) : null)
                .rawContent(event.getContentString())
                .createTime(event.getData() != null ? event.getData().getCreateTime() : null)
                .build();

        try {
            chatRecordRepo.save(record);
            log.debug("[ChatRecord] 已存储 roomId={}, sender={}({}), botReply={}, content={}",
                    chatroomId, senderNick, senderWxid, isBotReply,
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
