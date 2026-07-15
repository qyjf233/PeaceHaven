package com.potato.peacehaven.controller;

import com.potato.peacehaven.config.WechatApiProperties;
import com.potato.peacehaven.entity.BotScheduleConfig;
import com.potato.peacehaven.entity.BotTimedMessage;
import com.potato.peacehaven.entity.BotMessageTemplate;
import com.potato.peacehaven.repository.BotScheduleConfigRepository;
import com.potato.peacehaven.repository.BotTimedMessageRepository;
import com.potato.peacehaven.repository.BotMessageTemplateRepository;
import com.potato.peacehaven.service.WechatApiConfigService;
import com.potato.peacehaven.service.WechatApiService;
import com.potato.peacehaven.service.WechatApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminBotController {

    private final BotScheduleConfigRepository scheduleRepo;
    private final BotTimedMessageRepository messageRepo;
    private final BotMessageTemplateRepository templateRepo;
    private final WechatApiProperties wechatApiProps;
    private final WechatApiService wechatApiService;
    private final WechatApiConfigService wechatApiConfigService;
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
        return Map.of("success", true, "data", data);
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
    public Map<String, Object> logoutDevice() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        if (!wechatApiProps.isDeviceBound()) {
            return Map.of("success", false, "message", "设备未绑定");
        }
        WechatApiResponse resp = wechatApiService.logout();
        if (resp.isSuccess()) {
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
    public Map<String, Object> checkLogin(@RequestBody Map<String, String> body) {
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
    public Map<String, Object> testMessage() {
        if (!wechatApiProps.isConfigured()) {
            return Map.of("success", false, "message", "API 未配置");
        }
        String groupId = wechatApiProps.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return Map.of("success", false, "message", "群聊 ID 未配置");
        }
        WechatApiResponse resp = wechatApiService.sendText(groupId, "✅ PeaceHaven 机器人测试消息，连接正常！");
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
            @RequestBody Map<String, String> body) {
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
        return Map.of("success", true);
    }

    // ===== API: 新增一次型日程（资源战/争霸赛） =====

    @ResponseBody
    @PostMapping("/api/bot/schedules")
    public Map<String, Object> addOnce(@RequestBody Map<String, String> body) {
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
        return Map.of("success", true, "id", cfg.getId());
    }

    // ===== API: 删除日程 =====

    @ResponseBody
    @DeleteMapping("/api/bot/schedules/{id}")
    public Map<String, Object> deleteSchedule(@PathVariable Long id) {
        scheduleRepo.deleteById(id);
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
    public Map<String, Object> addMessage(@RequestBody Map<String, Object> body) {
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
        return Map.of("success", true, "id", msg.getId());
    }

    // ===== API: 删除定时消息 =====

    @ResponseBody
    @DeleteMapping("/api/bot/messages/{id}")
    public Map<String, Object> deleteMessage(@PathVariable Long id) {
        messageRepo.deleteById(id);
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
    public Map<String, Object> saveTemplate(@RequestBody Map<String, Object> body) {
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
        return Map.of("success", true, "id", tpl.getId());
    }

    // ===== API: 删除消息模板 =====

    @ResponseBody
    @DeleteMapping("/api/bot/templates/{id}")
    public Map<String, Object> deleteTemplate(@PathVariable Long id) {
        templateRepo.deleteById(id);
        return Map.of("success", true);
    }

    // ===== Helper =====

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
}
