package com.potato.peacehaven.service;

import com.potato.peacehaven.config.WechatApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * WechatApi 全量接口封装
 * <p>
 * 基于 iPad 协议的个人微信 HTTP API 服务封装，覆盖：
 * - 登录与设备管理
 * - 消息收发（文本/图片/文件/语音/视频/链接）
 * - 好友管理（搜索/添加/通讯录/详情）
 * - 群聊管理（建群/拉人/踢人/成员列表/公告/二维码）
 * - 朋友圈（发布文字/图片/点赞）
 * <p>
 * 鉴权方式：
 * - 登录流程（getLoginQrCode/checkLogin/logout）：请求头 wechat-token
 * - 其他接口（checkOnline/reconnection/setCallback/消息/联系人/群/朋友圈）：请求头 VideosApi-token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatApiService {

    private final WechatApiProperties props;
    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }

    // ========================================================================
    //  内部工具
    // ========================================================================

    /** 构建带鉴权头的 HTTP Headers（VideosApi-token，用于大多数接口） */
    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("VideosApi-token", props.getToken());
        return h;
    }

    /** 构建登录流程专用 Headers（wechat-token） */
    private HttpHeaders loginHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("wechat-token", props.getToken());
        return h;
    }

    /** 通用 POST 请求（使用 VideosApi-token） */
    private WechatApiResponse post(String path, Map<String, Object> body) {
        return postWithHeaders(path, body, authHeaders());
    }

    /** POST 请求（登录流程专用，使用 wechat-token） */
    private WechatApiResponse postLogin(String path, Map<String, Object> body) {
        return postWithHeaders(path, body, loginHeaders());
    }

    /** POST 请求（指定 Headers） */
    private WechatApiResponse postWithHeaders(String path, Map<String, Object> body, HttpHeaders headers) {
        String url = props.getBaseUrl() + path;
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        log.info("WechatApi 请求 [{}] URL: {}", path, url);
        log.info("WechatApi 请求 [{}] Headers: {}", path, headers);
        log.info("WechatApi 请求 [{}] Body: {}", path, body);
        try {
            ResponseEntity<WechatApiResponse> resp = restTemplate.exchange(
                    url, HttpMethod.POST, entity, WechatApiResponse.class);
            WechatApiResponse result = resp.getBody();
            if (result == null) {
                result = new WechatApiResponse();
                result.setRet(500);
                result.setMsg("Empty response from WechatApi");
            }
            log.info("WechatApi 响应 [{}] ret={}, msg={}, data={}", path, result.getRet(), result.getMsg(), result.getData());
            return result;
        } catch (RestClientException e) {
            log.error("WechatApi 请求失败 [{}]: {}", path, e.getMessage());
            WechatApiResponse err = new WechatApiResponse();
            err.setRet(500);
            err.setMsg("HTTP error: " + e.getMessage());
            return err;
        }
    }

    /** 构建包含 appId 的基础请求体 */
    private Map<String, Object> baseBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", props.getAppId());
        return m;
    }

    /** 构建请求体并附加额外参数 */
    private Map<String, Object> bodyWith(Object... kvPairs) {
        Map<String, Object> m = baseBody();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            m.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return m;
    }

    // ========================================================================
    //  1. 登录与设备管理
    // ========================================================================

    /**
     * 获取登录二维码
     * <p>首次登录：appId 传空，系统自动创建新设备
     * <p>二次登录（掉线重连）：必须传入上次的 appId，覆盖登录同一虚拟设备
     *
     * @param existingAppId 已有的 appId（首次登录传 null 或空串）
     */
    public WechatApiResponse getLoginQrCode(String existingAppId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", existingAppId != null ? existingAppId : "");
        body.put("regionId", "330000");
        body.put("type", "ipad");
        return postLogin("/login/getLoginQrCode", body);
    }

    /**
     * 轮询登录状态（步骤2：执行登录）
     * <p>扫码后每 5 秒调用一次，二维码超时 120 秒
     * <p>响应 data.status: 0=未扫码, 1=已扫码未确认, 2=登录成功, 4=取消
     * <p>登录成功时 data.loginInfo={uin,wxid,nickName,mobile,alias}
     *
     * @param appId QR 响应中的 appId
     * @param uuid  QR 响应中的 uuid
     */
    public WechatApiResponse checkLogin(String appId, String uuid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", appId);
        body.put("uuid", uuid);
        body.put("autoSliding", false);
        return postLogin("/login/checkLogin", body);
    }

    /** 检查账号在线状态（data=true 在线，data=false 离线） */
    public WechatApiResponse checkOnline() {
        return post("/login/checkOnline", baseBody());
    }

    /** 判断 checkOnline 响应是否为在线（data=true） */
    public static boolean isOnlineResponse(WechatApiResponse resp) {
        if (!resp.isSuccess() || resp.getData() == null) return false;
        if (resp.getData() instanceof Boolean) return (Boolean) resp.getData();
        return false;
    }

    /**
     * 异常断线重连
     * <p>场景：手机显示在线但后台离线 / 收不到消息回调
     */
    public WechatApiResponse reconnect() {
        return post("/login/reconnection", baseBody());
    }

    /** 退出登录（wechat-token） */
    public WechatApiResponse logout() {
        return postLogin("/login/logout", baseBody());
    }

    /**
     * 设置消息回调地址
     * <p>注意：body 发送的是 token + callbackUrl，不需要 appId
     */
    public WechatApiResponse setCallback(String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", props.getToken());
        body.put("callbackUrl", callbackUrl);
        return post("/login/setCallback", body);
    }

    /** 取消消息回调 */
    public WechatApiResponse removeCallback() {
        return post("/tools/removeCallback", baseBody());
    }

    // ========================================================================
    //  2. 消息收发
    // ========================================================================

    /**
     * 发送文本消息
     *
     * @param toWxid 接收方 wxid（好友）或 xxx@chatroom（群聊）
     * @param content 消息正文，支持 \n 换行
     * @param mentionList @成员的 wxid 列表（仅群聊有效），可为 null
     * @param mentionAll 是否 @所有人（群聊有效，需群主或管理员权限）
     */
    public WechatApiResponse sendText(String toWxid, String content,
                                      List<String> mentionList, boolean mentionAll) {
        // @全体：ats="notify@all"，content 需包含 @所有人
        if (mentionAll) {
            if (!content.contains("@所有人")) {
                content = "@所有人 " + content;
            }
            Map<String, Object> body = bodyWith("toWxid", toWxid, "content", content);
            body.put("ats", "notify@all");
            return post("/message/postText", body);
        }

        // @特定成员：ats=wxid1,wxid2，content 需包含 @昵称（由调用方保证）
        Map<String, Object> body = bodyWith("toWxid", toWxid, "content", content);
        if (mentionList != null && !mentionList.isEmpty()) {
            body.put("ats", String.join(",", mentionList));
        }
        return post("/message/postText", body);
    }

    /** 发送文本消息（简化版，无@） */
    public WechatApiResponse sendText(String toWxid, String content) {
        return sendText(toWxid, content, null, false);
    }

    /** 发送文本消息到配置的群聊 */
    public WechatApiResponse sendTextToGroup(String content,
                                             List<String> mentionList, boolean mentionAll) {
        return sendText(props.getGroupId(), content, mentionList, mentionAll);
    }

    /** 发送图片消息 */
    public WechatApiResponse sendImage(String toWxid, String imgUrl) {
        return post("/message/postImage", bodyWith("toWxid", toWxid, "imgUrl", imgUrl));
    }

    /** 发送文件消息 */
    public WechatApiResponse sendFile(String toWxid, String fileUrl, String fileName) {
        return post("/message/postFile", bodyWith("toWxid", toWxid, "fileUrl", fileUrl, "fileName", fileName));
    }

    /** 发送语音消息 */
    public WechatApiResponse sendVoice(String toWxid, String voiceUrl, int voiceDuration) {
        return post("/message/postVoice", bodyWith("toWxid", toWxid, "voiceUrl", voiceUrl, "voiceDuration", voiceDuration));
    }

    /** 发送视频消息 */
    public WechatApiResponse sendVideo(String toWxid, String videoUrl, String thumbUrl) {
        return post("/message/postVideo", bodyWith("toWxid", toWxid, "videoUrl", videoUrl, "thumbUrl", thumbUrl));
    }

    /** 发送链接卡片消息 */
    public WechatApiResponse sendLink(String toWxid, String title, String desc,
                                      String linkUrl, String thumbUrl) {
        return post("/message/postLink", bodyWith(
                "toWxid", toWxid, "title", title, "desc", desc,
                "linkUrl", linkUrl, "thumbUrl", thumbUrl));
    }

    // ========================================================================
    //  3. 好友管理
    // ========================================================================

    /** 按微信号/手机号搜索用户 */
    public WechatApiResponse searchContact(String keyword) {
        return post("/contacts/search", bodyWith("keyword", keyword));
    }

    /** 发起好友申请 */
    public WechatApiResponse addContact(String toWxid, String remark) {
        return post("/contacts/addContacts", bodyWith("toWxid", toWxid, "remark", remark));
    }

    /** 拉取通讯录好友列表 */
    public WechatApiResponse fetchContactsList() {
        return post("/contacts/fetchContactsList", baseBody());
    }

    /** 获取指定好友的详细信息（头像、昵称、地区等） */
    public WechatApiResponse getContactDetail(String toWxid) {
        return post("/contacts/getDetailInfo", bodyWith("toWxid", toWxid));
    }

    /** 删除好友 */
    public WechatApiResponse deleteContact(String toWxid) {
        return post("/contacts/deleteContacts", bodyWith("toWxid", toWxid));
    }

    /** 接受好友请求（被动添加好友） */
    public WechatApiResponse acceptFriend(String v3, String v4) {
        return post("/contacts/acceptNewFriend", bodyWith("v3", v3, "v4", v4));
    }

    // ========================================================================
    //  4. 群聊管理
    // ========================================================================

    /** 创建群聊（传入初始成员 wxid 列表，至少2人+自己共3人起群） */
    public WechatApiResponse createChatroom(List<String> memberList) {
        return post("/group/createChatroom", bodyWith("wxids", memberList));
    }

    /** 邀请成员入群 */
    public WechatApiResponse inviteMember(String chatroomId, List<String> memberList) {
        return post("/group/inviteMember", bodyWith("chatroomId", chatroomId, "wxids", String.join(",", memberList), "reason", ""));
    }

    /** 移除群成员（需群主或管理员权限） */
    public WechatApiResponse removeMember(String chatroomId, List<String> memberList) {
        return post("/group/removeMember", bodyWith("chatroomId", chatroomId, "wxids", String.join(",", memberList)));
    }

    /** 获取群成员列表 */
    public WechatApiResponse getChatroomMemberList(String chatroomId) {
        return post("/group/getChatroomMemberList", bodyWith("chatroomId", chatroomId));
    }

    /** 获取配置的群聊成员列表 */
    public WechatApiResponse getConfiguredGroupMembers() {
        return getChatroomMemberList(props.getGroupId());
    }

    /** 设置群公告 */
    public WechatApiResponse setChatroomAnnouncement(String chatroomId, String announcement) {
        return post("/group/setChatroomAnnouncement",
                bodyWith("chatroomId", chatroomId, "announcement", announcement));
    }

    /** 获取群二维码 */
    public WechatApiResponse getChatroomQrCode(String chatroomId) {
        return post("/group/getChatroomQrCode", bodyWith("chatroomId", chatroomId));
    }

    /** 获取群详情（群名、群公告、群主等） */
    public WechatApiResponse getChatroomDetail(String chatroomId) {
        return post("/group/getChatroomDetailInfo", bodyWith("chatroomId", chatroomId));
    }

    /** 修改群名称（需群主权限） */
    public WechatApiResponse setChatroomName(String chatroomId, String name) {
        return post("/group/setChatroomName", bodyWith("chatroomId", chatroomId, "name", name));
    }

    /** 退出群聊 */
    public WechatApiResponse quitChatroom(String chatroomId) {
        return post("/group/quitChatroom", bodyWith("chatroomId", chatroomId));
    }

    // ========================================================================
    //  5. 朋友圈
    // ========================================================================

    /** 发布纯文字朋友圈 */
    public WechatApiResponse postTextMoments(String content) {
        return post("/sns/sendTextSns", bodyWith("content", content));
    }

    /** 发布带图片的朋友圈 */
    public WechatApiResponse postImageMoments(String content, List<String> imgUrls) {
        return post("/sns/sendImgSns", bodyWith("content", content, "imgUrls", imgUrls));
    }

    /** 点赞朋友圈 */
    public WechatApiResponse likeMoments(String momentId, String userWxid) {
        return post("/sns/likeSns", bodyWith("momentId", momentId, "userWxid", userWxid));
    }

    // ========================================================================
    //  6. 工具方法
    // ========================================================================

    /**
     * 从 Webhook 回调的 fromWxid 判断是否为群消息
     * 群消息的 fromWxid 格式为 xxxxxxxx@chatroom
     */
    public static boolean isGroupMessage(String fromWxid) {
        return fromWxid != null && fromWxid.endsWith("@chatroom");
    }

    /**
     * 从群消息 content 中提取 @机器人 后的纯文本
     *
     * @param content  原始消息内容
     * @param botName  机器人微信昵称
     * @return 去掉 @前缀后的文本，若未 @机器人则返回 null
     */
    public static String extractMentionText(String content, String botName) {
        if (content == null || botName == null) return null;
        String prefix = "@" + botName;
        int idx = content.indexOf(prefix);
        if (idx < 0) return null;
        return content.substring(idx + prefix.length()).trim();
    }
}
