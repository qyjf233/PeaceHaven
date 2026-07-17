package com.potato.peacehaven.controller;

import com.potato.peacehaven.ai.memory.MemoryBootstrapService;
import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
import com.potato.peacehaven.service.AdminOperationLogService;
import com.potato.peacehaven.service.AiWhitelistService;
import com.potato.peacehaven.service.WechatApiConfigService;
import com.potato.peacehaven.service.WechatApiService;
import com.potato.peacehaven.service.WechatApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminBotController {

    private final BotScheduleConfigRepository scheduleRepo;
    private final BotTimedMessageRepository messageRepo;
    private final BotMessageTemplateRepository templateRepo;
    private final BotGroupMemberRepository groupMemberRepo;
    private final CampMemberRepository campMemberRepo;
    private final WechatApiProperties wechatApiProps;
    private final WechatApiService wechatApiService;
    private final WechatApiConfigService wechatApiConfigService;
    private final AdminOperationLogService logService;
    private final AiWhitelistService aiWhitelistService;
    private final MemoryBootstrapService memoryBootstrapService;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @GetMapping("/bot")
    public String botConfig(Model model) {
        List<BotScheduleConfig> all = scheduleRepo.findAll();
        Map<String, List<BotScheduleConfig>> byType = all.stream()
                .collect(Collectors.groupingBy(BotScheduleConfig::getEventType));
        model.addAttribute("scheduleByType", byType);
        // 基本配置状态
        model.addAttribute("apiConfigured", wechatApiProps.isConfigured());
        model.addAttribute("apiBaseUrl", wechatApiProps.getBaseUrl());
        model.addAttribute("apiAppId", wechatApiProps.getAppId());
        model.addAttribute("apiCallbackUrl", wechatApiProps.getCallbackUrl());
        model.addAttribute("apiGroupId", wechatApiProps.getGroupId());
        model.addAttribute("pushEnabled", wechatApiProps.isPushEnabled());
        return "admin/bot-config";
    }

    // ===== API: 基本配置状态 =====

    @ResponseBody
    @GetMapping("/api/bot/config/status")
    public Map<String, Object> getConfigStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("configured", wechatApiProps.isConfigured());
        data.put("deviceBound", wechatApiProps.isDeviceBound());
        data.put("baseUrl", wechatApiProps.getBaseUrl());
        data.put("appId", wechatApiProps.getAppId());
        data.put("callbackUrl", wechatApiProps.getCallbackUrl());
        data.put("groupId", wechatApiProps.getGroupId());
        data.put("pushEnabled", wechatApiProps.isPushEnabled());
        return Map.of("success", true, "data", data);
    }

    // ===== API: 推送开关 =====

    @ResponseBody
    @PostMapping("/api/bot/config/push-toggle")
    public Map<String, Object> setPushEnabled(@RequestBody Map<String, Boolean> body, HttpServletRequest request) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return Map.of("success", false, "message", "缺少 enabled 参数");
        }
        wechatApiConfigService.setPushEnabled(enabled);
        logService.record("机器人配置", "修改", (enabled ? "开启" : "关闭") + "定时推送", request);
        return Map.of("success", true, "message", Boolean.TRUE.equals(enabled) ? "已开启定时推送" : "已关闭定时推送");
    }

    // ===== API: 连接检测（checkOnline + setCallback） =====

    @ResponseBody
    @PostMapping("/api/bot/config/reconnect")
    public Map<String, Object> reconnect() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 配置不完整（缺少 baseUrl 或 token）");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定（缺少 appId），需重新扫码登录");
        }

        Map<String, Object> data = new HashMap<>();

        // Step 1: 检查在线（data=true 在线，data=false 离线）
        WechatApiResponse onlineResp = wechatApiService.checkOnline();
        boolean online = WechatApiService.isOnlineResponse(onlineResp);
        data.put("online", online);
        data.put("onlineMsg", onlineResp.getMsg());
        if (!online) {
            data.put("needRelogin", true);
            return Map.of("success", true, "data", data,
                    "message", "设备离线，需重新扫码登录");
        }

        // Step 2: 注册回调
        String callbackUrl = wechatApiProps.getCallbackUrl();
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            WechatApiResponse cbResp = wechatApiService.setCallback(callbackUrl);
            data.put("callbackOk", cbResp.isSuccess());
            data.put("callbackMsg", cbResp.getMsg());
        } else {
            data.put("callbackOk", false);
            data.put("callbackMsg", "回调地址未配置");
        }

        data.put("needRelogin", false);
        return Map.of("success", true, "data", data, "message", "连接检测完成");
    }

    // ===== API: 检查在线状态 =====

    @ResponseBody
    @PostMapping("/api/bot/config/checkOnline")
    public Map<String, Object> checkOnline() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        WechatApiResponse resp = wechatApiService.checkOnline();
        boolean online = WechatApiService.isOnlineResponse(resp);
        if (online) {
            return Map.of("success", true, "online", true, "message", "设备在线");
        }
        return Map.of("success", true, "online", false, "message", resp.getMsg());
    }

    // ===== API: 退出登录 =====

    @ResponseBody
    @PostMapping("/api/bot/config/logout")
    public Map<String, Object> logoutDevice(HttpServletRequest request) {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        WechatApiResponse resp = wechatApiService.logout();
        if (resp.isSuccess()) {
            logService.record("机器人配置", "退出登录", "退出微信设备登录", request);
            return Map.of("success", true, "message", "已退出登录");
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "退出失败");
    }

    // ===== API: 扫码登录流程 =====

    /** 获取登录二维码 */
    @ResponseBody
    @GetMapping("/api/bot/config/loginQrCode")
    public Map<String, Object> getLoginQrCode() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 配置不完整（缺少 baseUrl 或 token）");
        }
        WechatApiResponse resp = wechatApiService.getLoginQrCode(wechatApiProps.getAppId());
        if (resp.isSuccess() && resp.getData() != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("qrUrl", resp.getString("qrUrl"));
            data.put("qrImgBase64", resp.getString("qrImgBase64"));
            data.put("uuid", resp.getString("uuid"));
            data.put("appId", resp.getString("appId"));
            return Map.of("success", true, "data", data);
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "获取二维码失败");
    }

    /**
     * 轮询登录状态
     * <p>data.status: 0=未扫码, 1=已扫未确认, 2=登录成功, 4=取消
     * <p>登录成功时 data.loginInfo={uin,wxid,nickName,mobile,alias}
     */
    @ResponseBody
    @PostMapping("/api/bot/config/checkLogin")
    public Map<String, Object> checkLogin(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String appId = body.get("appId");
        String uuid = body.get("uuid");
        if (appId == null || uuid == null) {
            return Map.of("success", false, "message", "缺少 appId 或 uuid");
        }

        WechatApiResponse resp = wechatApiService.checkLogin(appId, uuid);
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> respData = resp.getDataAsMap();

        if (!resp.isSuccess()) {
            return Map.of("success", true, "data", data, "loggedIn", false,
                    "message", resp.getMsg() != null ? resp.getMsg() : "查询失败");
        }

        // 解析 status 字段
        Integer status = null;
        if (respData != null) {
            Object statusObj = respData.get("status");
            if (statusObj instanceof Number) {
                status = ((Number) statusObj).intValue();
            }
        }

        if (status == null || status == 0 || status == 1) {
            // 未扫码或已扫未确认 → 继续轮询
            String nickName = respData != null ? (String) respData.get("nickName") : null;
            if (nickName != null) data.put("nickName", nickName);
            return Map.of("success", true, "data", data, "loggedIn", false,
                    "status", status != null ? status : 0,
                    "message", status != null && status == 1 ? "已扫码，等待确认" : "等待扫码");
        }

        if (status == 4) {
            return Map.of("success", true, "data", data, "loggedIn", false,
                    "status", 4, "message", "已取消登录");
        }

        // status == 2 → 登录成功
        wechatApiConfigService.updateAppId(appId);
        logService.record("机器人配置", "登录", "扫码登录成功 appId=" + appId, request);

        Object loginInfo = respData != null ? respData.get("loginInfo") : null;
        if (loginInfo instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> info = (Map<String, Object>) loginInfo;
            data.put("wxid", info.get("wxid"));
            data.put("nickName", info.get("nickName"));
            data.put("uin", info.get("uin"));
        }
        // data 层也有 nickName/headImgUrl
        if (respData != null && respData.get("nickName") != null) data.put("nickName", respData.get("nickName"));
        if (respData != null && respData.get("headImgUrl") != null) data.put("headImgUrl", respData.get("headImgUrl"));
        data.put("appId", appId);
        return Map.of("success", true, "data", data, "loggedIn", true,
                "status", 2, "message", "登录成功");
    }

    // ===== API: 注册回调地址 =====

    @ResponseBody
    @PostMapping("/api/bot/config/setCallback")
    public Map<String, Object> setCallback() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        String callbackUrl = wechatApiProps.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Map.of("success", false, "message", "回调地址未配置");
        }
        WechatApiResponse resp = wechatApiService.setCallback(callbackUrl);
        return Map.of("success", resp.isSuccess(), "message", resp.getMsg());
    }

    // ===== API: 发送测试消息 =====

    @ResponseBody
    @PostMapping("/api/bot/config/testMessage")
    public Map<String, Object> testMessage(HttpServletRequest request) {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }
        WechatApiResponse resp = wechatApiService.sendText(groupId, "✅ PeaceHaven 机器人测试消息，连接正常！");
        if (resp.isSuccess()) {
            logService.record("机器人配置", "测试", "发送测试消息到群聊", request);
        }
        return Map.of("success", resp.isSuccess(), "message", resp.isSuccess() ? "发送成功" : resp.getMsg());
    }

    // ===== API: 获取全部日程配置 =====

    @ResponseBody
    @GetMapping("/api/bot/schedules")
    public Map<String, Object> getSchedules() {
        List<BotScheduleConfig> all = scheduleRepo.findAll();
        Map<String, List<BotScheduleConfig>> byType = all.stream()
                .collect(Collectors.groupingBy(BotScheduleConfig::getEventType));
        return Map.of("success", true, "data", byType);
    }

    // ===== API: 更新固定周期日程（尸潮/铁手/巡逻） =====

    @ResponseBody
    @PutMapping("/api/bot/schedules/{id}")
    public Map<String, Object> updateFixed(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        BotScheduleConfig cfg = scheduleRepo.findById(id).orElse(null);
        if (cfg == null) {
            return Map.of("success", false, "message", "记录不存在");
        }
        String time = body.get("eventTime");
        if (time != null && !time.isBlank()) {
            cfg.setEventTime(LocalTime.parse(time, TIME_FMT));
        }
        String dow = body.get("dayOfWeek");
        if (dow != null && !dow.isBlank()) {
            cfg.setDayOfWeek(Integer.parseInt(dow));
        }
        scheduleRepo.save(cfg);
        logService.record("机器人配置", "修改", "更新日程 " + cfg.getEventType() + " 时间：" + (time != null ? time : "-") + " 周几：" + (dow != null ? dow : "-"), request);
        return Map.of("success", true);
    }

    // ===== API: 新增一次型日程（资源战/争霸赛） =====

    @ResponseBody
    @PostMapping("/api/bot/schedules")
    public Map<String, Object> addOnce(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String type = body.get("eventType");
        String dateStr = body.get("eventDate");
        String timeStr = body.get("eventTime");
        if (type == null || dateStr == null || timeStr == null) {
            return Map.of("success", false, "message", "参数不完整");
        }
        LocalDate date = LocalDate.parse(dateStr);
        LocalTime time = LocalTime.parse(timeStr, TIME_FMT);

        // 争霸赛需要解析 dayOfWeek
        Integer dayOfWeek = null;
        String dowStr = body.get("dayOfWeek");
        if (dowStr != null && !dowStr.isBlank()) {
            dayOfWeek = Integer.parseInt(dowStr);
        }

        // 同一天+同类型只能配一条（资源战/争霸赛）
        if (!"约战".equals(type)) {
            final Integer dow = dayOfWeek;
            boolean exists = scheduleRepo.findByEventType(type).stream()
                    .anyMatch(c -> date.equals(c.getEventDate())
                            && (dow == null || dow.equals(c.getDayOfWeek())));
            if (exists) {
                return Map.of("success", false, "message", "该日期已存在配置");
            }
        }

        BotScheduleConfig cfg = BotScheduleConfig.builder()
                .eventType(type)
                .eventDate(date)
                .eventTime(time)
                .dayOfWeek(dayOfWeek)
                .build();
        scheduleRepo.save(cfg);
        logService.record("机器人配置", "新增", "新增日程 " + type + " 日期：" + dateStr + " 时间：" + timeStr, request);
        return Map.of("success", true, "id", cfg.getId());
    }

    // ===== API: 删除日程 =====

    @ResponseBody
    @DeleteMapping("/api/bot/schedules/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable Long id, HttpServletRequest request) {
        BotScheduleConfig cfg = scheduleRepo.findById(id).orElse(null);
        scheduleRepo.deleteById(id);
        logService.record("机器人配置", "删除", "删除日程 " + (cfg != null ? cfg.getEventType() : id), request);
        return Map.of("success", true);
    }

    // ===== API: 获取定时消息列表 =====

    @ResponseBody
    @GetMapping("/api/bot/messages/{eventType}")
    public Map<String, Object> getMessages(@PathVariable String eventType) {
        List<BotTimedMessage> msgs = messageRepo.findByEventTypeOrderByAdvanceMinutesDesc(eventType);
        return Map.of("success", true, "data", msgs);
    }

    // ===== API: 新增定时消息 =====

    @ResponseBody
    @PostMapping("/api/bot/messages")
    public Map<String, Object> addMessage(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String eventType = (String) body.get("eventType");
        Object advObj = body.get("advanceMinutes");
        Object menObj = body.get("mentionAll");
        String text = (String) body.get("messageText");

        if (eventType == null || advObj == null) {
            return Map.of("success", false, "message", "参数不完整");
        }

        int advanceMinutes;
        if (advObj instanceof Number) {
            advanceMinutes = ((Number) advObj).intValue();
        } else {
            advanceMinutes = Integer.parseInt(advObj.toString());
        }

        boolean mentionAll = false;
        if (menObj instanceof Boolean) {
            mentionAll = (Boolean) menObj;
        } else if (menObj != null) {
            mentionAll = Boolean.parseBoolean(menObj.toString());
        }

        BotTimedMessage msg = BotTimedMessage.builder()
                .eventType(eventType)
                .advanceMinutes(advanceMinutes)
                .mentionAll(mentionAll)
                .messageText(text != null && !text.isBlank() ? text.trim() : null)
                .build();
        messageRepo.save(msg);
        logService.record("机器人配置", "新增", "新增定时消息 " + eventType + " 提前" + advanceMinutes + "分钟" + (mentionAll ? " @全体" : ""), request);
        return Map.of("success", true, "id", msg.getId());
    }

    // ===== API: 删除定时消息 =====

    @ResponseBody
    @DeleteMapping("/api/bot/messages/{id}")
    public Map<String, Object> deleteMessage(@PathVariable Long id, HttpServletRequest request) {
        BotTimedMessage msg = messageRepo.findById(id).orElse(null);
        messageRepo.deleteById(id);
        logService.record("机器人配置", "删除", "删除定时消息 " + (msg != null ? msg.getEventType() + " 提前" + msg.getAdvanceMinutes() + "分钟" : id.toString()), request);
        return Map.of("success", true);
    }

    // ===== API: 获取消息模板 =====

    @ResponseBody
    @GetMapping("/api/bot/templates/{eventType}")
    public Map<String, Object> getTemplates(@PathVariable String eventType) {
        List<BotMessageTemplate> templates = templateRepo.findByEventType(eventType);
        return Map.of("success", true, "data", templates);
    }

    // ===== API: 保存消息模板（新增或更新默认模板） =====

    @ResponseBody
    @PostMapping("/api/bot/templates")
    public Map<String, Object> saveTemplate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String eventType = (String) body.get("eventType");
        String text = (String) body.get("templateText");
        if (eventType == null || text == null || text.isBlank()) {
            return Map.of("success", false, "message", "模板文本不能为空");
        }

        Long timedMsgId = null;
        Object tmIdObj = body.get("timedMessageId");
        if (tmIdObj instanceof Number) {
            timedMsgId = ((Number) tmIdObj).longValue();
        } else if (tmIdObj instanceof String && !((String) tmIdObj).isBlank()) {
            timedMsgId = Long.parseLong((String) tmIdObj);
        }

        // 如果 timedMessageId 为 null，则是默认模板 —— 更新或新建
        if (timedMsgId == null) {
            BotMessageTemplate existing = templateRepo
                    .findByEventTypeAndTimedMessageIdIsNull(eventType).orElse(null);
            if (existing != null) {
                existing.setTemplateText(text.trim());
                templateRepo.save(existing);
                return Map.of("success", true, "id", existing.getId());
            }
        }

        BotMessageTemplate tpl = BotMessageTemplate.builder()
                .eventType(eventType)
                .timedMessageId(timedMsgId)
                .templateText(text.trim())
                .build();
        templateRepo.save(tpl);
        logService.record("机器人配置", "修改", "保存消息模板 " + eventType, request);
        return Map.of("success", true, "id", tpl.getId());
    }

    // ===== API: 删除消息模板 =====

    @ResponseBody
    @DeleteMapping("/api/bot/templates/{id}")
    public Map<String, Object> deleteTemplate(@PathVariable Long id, HttpServletRequest request) {
        BotMessageTemplate tpl = templateRepo.findById(id).orElse(null);
        templateRepo.deleteById(id);
        logService.record("机器人配置", "删除", "删除消息模板 " + (tpl != null ? tpl.getEventType() : id.toString()), request);
        return Map.of("success", true);
    }

    // ===== API: 获取群聊成员列表 =====

    @ResponseBody
    @GetMapping("/api/bot/group/members")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getGroupMembers() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }

        WechatApiResponse resp = wechatApiService.getConfiguredGroupMembers();
        if (!resp.isSuccess()) {
            return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "获取失败");
        }

        Map<String, Object> data = resp.getDataAsMap();
        if (data == null) {
            return Map.of("success", false, "message", "响应数据为空");
        }

        // 提取成员列表
        Object memberListObj = data.get("memberList");
        List<Map<String, Object>> members = (memberListObj instanceof List)
                ? (List<Map<String, Object>>) memberListObj
                : List.of();

        // 提取群主和管理员 wxid
        String owner = data.get("chatroomOwner") != null ? data.get("chatroomOwner").toString() : null;
        Object adminObj = data.get("adminWxid");
        List<String> admins = (adminObj instanceof List)
                ? ((List<Object>) adminObj).stream().map(Object::toString).collect(Collectors.toList())
                : List.of();

        // 给每个成员添加角色标记 + 营地成员标记
        Set<String> campNicknames = campMemberRepo.findAll().stream()
                .map(CampMember::getNickname)
                .collect(Collectors.toSet());

        for (Map<String, Object> m : members) {
            String wxid = m.get("wxid") != null ? m.get("wxid").toString() : "";
            if (wxid.equals(owner)) {
                m.put("role", "owner");
            } else if (admins.contains(wxid)) {
                m.put("role", "admin");
            } else {
                m.put("role", "member");
            }
            // 营地成员标记：displayName 优先，fallback nickName
            String effectiveName = m.get("displayName") != null ? m.get("displayName").toString() : null;
            if (effectiveName == null || effectiveName.isBlank()) {
                effectiveName = m.get("nickName") != null ? m.get("nickName").toString() : "";
            }
            m.put("isCampMember", campNicknames.contains(effectiveName));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("members", members);
        result.put("total", members.size());
        result.put("owner", owner);
        result.put("admins", admins);
        return Map.of("success", true, "data", result);
    }

    // ===== API: 踢出群成员 =====

    @ResponseBody
    @PostMapping("/api/bot/group/kick")
    public Map<String, Object> kickMember(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }
        String wxid = body.get("wxid");
        if (wxid == null || wxid.isBlank()) {
            return Map.of("success", false, "message", "缺少 wxid");
        }

        WechatApiResponse resp = wechatApiService.removeMember(groupId, List.of(wxid));
        if (resp.isSuccess()) {
            // 踢出成功后，检查此成员是否也是营地成员，如是则一并删除
            boolean campMemberRemoved = removeLinkedCampMember(wxid);
            // 同时从群成员表中删除
            groupMemberRepo.findByWxid(wxid).ifPresent(groupMemberRepo::delete);
            logService.record("机器人配置", "删除", "踢出群成员 " + wxid + (campMemberRemoved ? "（同时移除营地成员）" : ""), request);
            Map<String, Object> result = new HashMap<>();
            result.put("campMemberRemoved", campMemberRemoved);
            return Map.of("success", true, "message", "已踢出群聊", "data", result);
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "踢出失败");
    }

    // ===== API: 同步群成员到数据库 =====

    @ResponseBody
    @PostMapping("/api/bot/group/sync")
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> syncGroupMembers(HttpServletRequest request) {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }

        WechatApiResponse resp = wechatApiService.getConfiguredGroupMembers();
        if (!resp.isSuccess()) {
            return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "获取失败");
        }

        Map<String, Object> data = resp.getDataAsMap();
        if (data == null) {
            return Map.of("success", false, "message", "响应数据为空");
        }

        Object memberListObj = data.get("memberList");
        List<Map<String, Object>> members = (memberListObj instanceof List)
                ? (List<Map<String, Object>>) memberListObj
                : List.of();

        String owner = data.get("chatroomOwner") != null ? data.get("chatroomOwner").toString() : null;
        Object adminObj = data.get("adminWxid");
        List<String> admins = (adminObj instanceof List)
                ? ((List<Object>) adminObj).stream().map(Object::toString).collect(Collectors.toList())
                : List.of();

        LocalDateTime now = LocalDateTime.now();
        List<String> wxids = new ArrayList<>();
        int added = 0, updated = 0;

        for (Map<String, Object> m : members) {
            String wxid = m.get("wxid") != null ? m.get("wxid").toString() : "";
            if (wxid.isBlank()) continue;
            wxids.add(wxid);

            String role = wxid.equals(owner) ? "owner"
                        : admins.contains(wxid) ? "admin" : "member";

            Optional<BotGroupMember> existing = groupMemberRepo.findByWxid(wxid);
            if (existing.isPresent()) {
                BotGroupMember gm = existing.get();
                gm.setNickName(strOrNull(m.get("nickName")));
                gm.setDisplayName(strOrNull(m.get("displayName")));
                gm.setBigHeadImgUrl(strOrNull(m.get("bigHeadImgUrl")));
                gm.setSmallHeadImgUrl(strOrNull(m.get("smallHeadImgUrl")));
                gm.setRole(role);
                gm.setSyncedAt(now);
                groupMemberRepo.save(gm);
                updated++;
            } else {
                BotGroupMember gm = BotGroupMember.builder()
                        .wxid(wxid)
                        .nickName(strOrNull(m.get("nickName")))
                        .displayName(strOrNull(m.get("displayName")))
                        .bigHeadImgUrl(strOrNull(m.get("bigHeadImgUrl")))
                        .smallHeadImgUrl(strOrNull(m.get("smallHeadImgUrl")))
                        .role(role)
                        .syncedAt(now)
                        .build();
                groupMemberRepo.save(gm);
                added++;
            }
        }

        // 清理已退群成员
        groupMemberRepo.deleteByWxidNotIn(wxids);
        int removed = (int) groupMemberRepo.count() - wxids.size();
        if (removed < 0) removed = 0;

        Map<String, Object> result = new HashMap<>();
        result.put("total", wxids.size());
        result.put("added", added);
        result.put("updated", updated);
        result.put("removed", removed);
        logService.record("机器人配置", "同步", "同步群成员 共" + wxids.size() + "人 新增" + added + " 更新" + updated + " 清理" + removed, request);
        return Map.of("success", true, "data", result, "message", "同步完成");
    }

    // ===== API: 根据 wxid 踢出群聊（供营地成员删除页面调用） =====

    @ResponseBody
    @PostMapping("/api/bot/group/kick-by-wxid")
    public Map<String, Object> kickByWxid(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!wechatApiProps.isConfigured() || !wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未就绪");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }
        String wxid = body.get("wxid");
        if (wxid == null || wxid.isBlank()) {
            return Map.of("success", false, "message", "缺少 wxid");
        }

        WechatApiResponse resp = wechatApiService.removeMember(groupId, List.of(wxid));
        if (resp.isSuccess()) {
            // 同时从群成员表中删除
            groupMemberRepo.findByWxid(wxid).ifPresent(groupMemberRepo::delete);
            logService.record("机器人配置", "删除", "踢出群成员(营地联动) " + wxid, request);
            return Map.of("success", true, "message", "已踢出群聊");
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "踢出失败");
    }

    // ===== API: 群聊详情 =====

    @ResponseBody
    @GetMapping("/api/bot/group/detail")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getGroupDetail() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("groupId", groupId);
        String ownerWxid = null;

        // 获取群详情
        WechatApiResponse resp = wechatApiService.getChatroomDetail(groupId);
        if (resp.isSuccess() && resp.getData() != null) {
            Map<String, Object> rd = resp.getDataAsMap();
            if (rd != null) {
                data.put("groupName", rd.get("nickName") != null ? rd.get("nickName").toString() : groupId);
                data.put("announcement", rd.get("announcement"));
                Object ownerObj = rd.get("chatroomOwner");
                ownerWxid = ownerObj != null ? ownerObj.toString() : null;
                data.put("owner", ownerWxid);
                Object countObj = rd.get("memberCount");
                if (countObj instanceof Number) {
                    data.put("memberCount", ((Number) countObj).intValue());
                }
            }
        } else {
            data.put("groupName", groupId);
            data.put("apiError", resp.getMsg());
        }

        // 获取机器人自身 wxid
        WechatApiResponse profileResp = wechatApiService.getProfile();
        if (profileResp.isSuccess() && profileResp.getData() != null) {
            Map<String, Object> pd = profileResp.getDataAsMap();
            if (pd != null && pd.get("wxid") != null) {
                data.put("selfWxid", pd.get("wxid").toString());
            }
        }

        // 获取成员列表：解析群主昵称 + 管理员列表（含昵称）
        WechatApiResponse memberResp = wechatApiService.getConfiguredGroupMembers();
        if (memberResp.isSuccess() && memberResp.getData() != null) {
            Map<String, Object> md = memberResp.getDataAsMap();
            if (md != null) {
                Object memberListObj = md.get("memberList");
                Map<String, String> wxidNickMap = new HashMap<>();
                if (memberListObj instanceof List) {
                    for (Object m : (List<Object>) memberListObj) {
                        if (m instanceof Map) {
                            Map<String, Object> member = (Map<String, Object>) m;
                            String wxid = member.get("wxid") != null ? member.get("wxid").toString() : null;
                            String nick = member.get("displayName") != null ? member.get("displayName").toString()
                                    : (member.get("nickName") != null ? member.get("nickName").toString() : null);
                            if (wxid != null && nick != null) {
                                wxidNickMap.put(wxid, nick);
                            }
                            // 群主昵称
                            if (wxid != null && wxid.equals(ownerWxid)) {
                                data.put("ownerNick", nick);
                            }
                        }
                    }
                }

                // 管理员列表（含昵称）
                Object adminObj = md.get("adminWxid");
                List<Map<String, String>> adminList = new ArrayList<>();
                if (adminObj instanceof List) {
                    for (Object a : (List<Object>) adminObj) {
                        String wxid = a != null ? a.toString() : null;
                        if (wxid != null && !wxid.isBlank()) {
                            Map<String, String> admin = new HashMap<>();
                            admin.put("wxid", wxid);
                            admin.put("nick", wxidNickMap.getOrDefault(wxid, wxid));
                            adminList.add(admin);
                        }
                    }
                }
                data.put("adminList", adminList);
            }
        }

        return Map.of("success", true, "data", data);
    }

    // ===== API: 修改群名称 =====

    @ResponseBody
    @PostMapping("/api/bot/group/change-name")
    public Map<String, Object> changeGroupName(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!wechatApiProps.isConfigured() || !wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未就绪");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }
        String name = body.get("groupName");
        if (name == null || name.isBlank()) {
            return Map.of("success", false, "message", "群名称不能为空");
        }
        if (name.length() > 32) {
            return Map.of("success", false, "message", "群名称不能超过 32 个字符");
        }
        WechatApiResponse resp = wechatApiService.setChatroomName(groupId, name.trim());
        if (resp.isSuccess()) {
            logService.record("机器人配置", "修改", "修改群名称为：" + name.trim(), request);
            return Map.of("success", true, "message", "群名称已修改");
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "修改失败");
    }

    // ===== API: 修改机器人账号昵称 =====

    @ResponseBody
    @PostMapping("/api/bot/group/change-nickname")
    public Map<String, Object> changeOwnerNickname(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!wechatApiProps.isConfigured() || !wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未就绪");
        }
        String nickName = body.get("nickName");
        if (nickName == null || nickName.isBlank()) {
            return Map.of("success", false, "message", "昵称不能为空");
        }
        WechatApiResponse resp = wechatApiService.updateBotProfile(nickName.trim());
        if (resp.isSuccess()) {
            logService.record("机器人配置", "修改", "修改账号昵称为：" + nickName.trim(), request);
            return Map.of("success", true, "message", "昵称已修改");
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "修改失败");
    }

    // ===== API: 设置/取消管理员 =====

    @ResponseBody
    @PostMapping("/api/bot/group/admin-operate")
    public Map<String, Object> adminOperate(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!wechatApiProps.isConfigured() || !wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未就绪");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }
        String wxid = body.get("wxid") != null ? body.get("wxid").toString() : null;
        Boolean promote = body.get("promote") instanceof Boolean ? (Boolean) body.get("promote") : null;
        if (wxid == null || wxid.isBlank()) {
            return Map.of("success", false, "message", "缺少 wxid");
        }
        if (promote == null) {
            return Map.of("success", false, "message", "缺少 promote 参数");
        }
        int val = promote ? 1 : 0;
        WechatApiResponse resp = wechatApiService.adminOperate(groupId, wxid, val);
        if (resp.isSuccess()) {
            logService.record("机器人配置", "修改",
                    (promote ? "设置管理员：" : "取消管理员：") + wxid, request);
            return Map.of("success", true, "message", promote ? "已设为管理员" : "已取消管理员");
        }
        return Map.of("success", false, "message", resp.getMsg() != null ? resp.getMsg() : "操作失败");
    }

    // ===== Helper =====

    /**
     * 踢出群成员后，检查并删除关联的营地成员
     */
    private boolean removeLinkedCampMember(String wxid) {
        Optional<BotGroupMember> gmOpt = groupMemberRepo.findByWxid(wxid);
        if (gmOpt.isEmpty()) return false;

        BotGroupMember gm = gmOpt.get();
        String effectiveName = gm.getEffectiveName();

        List<CampMember> campMembers = campMemberRepo.findAll();
        for (CampMember cm : campMembers) {
            if (cm.getNickname().equals(effectiveName)) {
                campMemberRepo.delete(cm);
                return true;
            }
        }
        return false;
    }

    private String strOrNull(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private Map<String, Object> toConfigMap(BotScheduleConfig cfg) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", cfg.getId());
        m.put("eventType", cfg.getEventType());
        m.put("dayOfWeek", cfg.getDayOfWeek());
        m.put("eventTime", cfg.getEventTime() != null ? cfg.getEventTime().format(TIME_FMT) : null);
        m.put("eventDate", cfg.getEventDate() != null ? cfg.getEventDate().toString() : null);
        m.put("eventDatetime", cfg.getEventDatetime() != null ? cfg.getEventDatetime().toString() : null);
        return m;
    }

    // ===== API: AI 分身白名单 =====

    @ResponseBody
    @GetMapping("/api/bot/ai/whitelist")
    public Map<String, Object> getAiWhitelist() {
        List<BotAiWhitelist> list = aiWhitelistService.getWhitelist();
        List<Map<String, Object>> items = list.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("type", e.getType());
            m.put("wxid", e.getWxid());
            m.put("name", e.getName());
            m.put("trainingEnabled", e.getTrainingEnabled());
            m.put("replyEnabled", e.getReplyEnabled());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        return Map.of("success", true, "data", items);
    }

    @ResponseBody
    @PostMapping("/api/bot/ai/whitelist")
    @Transactional
    public Map<String, Object> addAiWhitelist(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String type = body.get("type");
        String wxid = body.get("wxid");
        String name = body.get("name");
        if (type == null || type.isBlank() || wxid == null || wxid.isBlank()) {
            return Map.of("success", false, "message", "type 和 wxid 不能为空");
        }
        if (!"group".equals(type) && !"friend".equals(type)) {
            return Map.of("success", false, "message", "type 必须为 group 或 friend");
        }
        BotAiWhitelist entry = aiWhitelistService.addEntry(type, wxid, name);
        logService.record("机器人配置", "新增", "AI白名单 type=" + type + ", wxid=" + wxid, request);
        return Map.of("success", true, "message", "已添加", "data", entry.getId());
    }

    @ResponseBody
    @DeleteMapping("/api/bot/ai/whitelist/{id}")
    @Transactional
    public Map<String, Object> removeAiWhitelist(@PathVariable Long id, HttpServletRequest request) {
        boolean removed = aiWhitelistService.removeEntry(id);
        if (removed) {
            logService.record("机器人配置", "删除", "AI白名单 id=" + id, request);
            return Map.of("success", true, "message", "已删除");
        }
        return Map.of("success", false, "message", "条目不存在");
    }

    @ResponseBody
    @PatchMapping("/api/bot/ai/whitelist/{id}")
    @Transactional
    public Map<String, Object> updateAiWhitelist(@PathVariable Long id, @RequestBody Map<String, Boolean> body, HttpServletRequest request) {
        Boolean trainingEnabled = body.get("trainingEnabled");
        Boolean replyEnabled = body.get("replyEnabled");
        if (trainingEnabled == null && replyEnabled == null) {
            return Map.of("success", false, "message", "trainingEnabled 或 replyEnabled 参数缺失");
        }
        boolean updated = aiWhitelistService.updateFlags(id, trainingEnabled, replyEnabled);
        if (updated) {
            String detail = "AI白名单 id=" + id;
            if (trainingEnabled != null) detail += " 训练=" + (trainingEnabled ? "开" : "关");
            if (replyEnabled != null) detail += " 回复=" + (replyEnabled ? "开" : "关");
            logService.record("机器人配置", "修改", detail, request);
            return Map.of("success", true, "message", "已更新");
        }
        return Map.of("success", false, "message", "条目不存在");
    }

    // ===== 用户画像补课 =====

    /**
     * 从历史聊天记录批量提取用户画像（补课功能）
     * <p>
     * 注意：会大量调用 LLM，每个用户约消耗 1-2s + 若干 token。
     * 建议仅在首次初始化或修复 bug 后使用一次。
     * </p>
     *
     * @param force 是否强制重新处理已有画像的用户（默认 false）
     */
    @PostMapping("/api/bot/memory/bootstrap")
    @ResponseBody
    public Map<String, Object> bootstrapMemory(
            @RequestParam(defaultValue = "false") boolean force,
            HttpServletRequest request) {
        logService.record("用户画像", "补课", "force=" + force, request);
        // 异步执行，避免 HTTP 超时
        CompletableFuture.runAsync(() -> memoryBootstrapService.bootstrap(force));
        return Map.of("success", true, "message", "补课任务已启动，查看日志 [MemoryBootstrap] 跟踪进度");
    }

    /**
     * 查询补课任务状态
     */
    @GetMapping("/api/bot/memory/bootstrap/status")
    @ResponseBody
    public Map<String, Object> bootstrapStatus() {
        return Map.of("running", memoryBootstrapService.isRunning());
    }
}
