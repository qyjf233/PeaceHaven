package com.potato.peacehaven.controller;

import com.potato.peacehaven.dto.ActivityFormDTO;
import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Controller
@RequestMapping("/admin/activities")
@RequiredArgsConstructor
public class AdminActivityController {

    private final ActivityService activityService;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activities", activityService.getActivities(null, 0, 100).getContent());
        model.addAttribute("activityService", activityService);
        return "admin/activities";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new ActivityFormDTO());
        model.addAttribute("isEdit", false);
        return "admin/activity-form";
    }

    @PostMapping
    public String create(@ModelAttribute ActivityFormDTO form, RedirectAttributes redirect) {
        Activity activity = mapToEntity(form);
        activityService.createActivity(activity);
        redirect.addFlashAttribute("message", "活动创建成功！");
        return "redirect:/admin/activities";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Activity activity = activityService.getActivityById(id);
        ActivityFormDTO form = mapToDTO(activity);
        model.addAttribute("form", form);
        model.addAttribute("isEdit", true);
        return "admin/activity-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute ActivityFormDTO form, RedirectAttributes redirect) {
        Activity activity = mapToEntity(form);
        activityService.updateActivity(id, activity);
        redirect.addFlashAttribute("message", "活动更新成功！");
        return "redirect:/admin/activities";
    }

    @PostMapping("/{id}/delete")
    public String deleteActivity(@PathVariable Long id, RedirectAttributes redirect) {
        activityService.deleteActivity(id);
        redirect.addFlashAttribute("message", "活动已删除！");
        return "redirect:/admin/activities";
    }

    // === Helper Methods ===

    private Activity mapToEntity(ActivityFormDTO form) {
        return Activity.builder()
                .slug(form.getSlug())
                .title(form.getTitle())
                .summary(form.getSummary())
                .thumbnail(form.getThumbnail())
                .startDate(form.getStartDate() != null && !form.getStartDate().isEmpty()
                        ? LocalDateTime.parse(form.getStartDate(), FORMATTER) : null)
                .endDate(form.getEndDate() != null && !form.getEndDate().isEmpty()
                        ? LocalDateTime.parse(form.getEndDate(), FORMATTER) : null)
                .build();
    }

    private ActivityFormDTO mapToDTO(Activity activity) {
        ActivityFormDTO dto = new ActivityFormDTO();
        dto.setId(activity.getId());
        dto.setSlug(activity.getSlug());
        dto.setTitle(activity.getTitle());
        dto.setSummary(activity.getSummary());
        dto.setThumbnail(activity.getThumbnail());
        dto.setStartDate(activity.getStartDate() != null
                ? activity.getStartDate().format(FORMATTER) : null);
        dto.setEndDate(activity.getEndDate() != null
                ? activity.getEndDate().format(FORMATTER) : null);
        return dto;
    }
}
