package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.BotScheduleConfig;
import com.potato.peacehaven.entity.BotTimedMessage;
import com.potato.peacehaven.entity.BotMessageTemplate;
import com.potato.peacehaven.repository.BotScheduleConfigRepository;
import com.potato.peacehaven.repository.BotTimedMessageRepository;
import com.potato.peacehaven.repository.BotMessageTemplateRepository;
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
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @GetMapping("/bot")
    public String botConfig(Model model) {
        List<BotScheduleConfig> all = scheduleRepo.findAll();
        Map<String, List<BotScheduleConfig>> byType = all.stream()
                .collect(Collectors.groupingBy(BotScheduleConfig::getEventType));
        model.addAttribute("scheduleByType", byType);
        return "admin/bot-config";
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
