package com.potato.peacehaven.controller;

import com.potato.peacehaven.dto.ActivityFormDTO;
import com.potato.peacehaven.entity.Activity;
import com.potato.peacehaven.entity.LeaderboardEntry;
import com.potato.peacehaven.entity.VoteOption;
import com.potato.peacehaven.enums.ActivityStatus;
import com.potato.peacehaven.enums.TemplateType;
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
        return "admin/activities";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new ActivityFormDTO());
        model.addAttribute("isEdit", false);
        model.addAttribute("templateTypes", TemplateType.values());
        model.addAttribute("statuses", ActivityStatus.values());
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
        model.addAttribute("templateTypes", TemplateType.values());
        model.addAttribute("statuses", ActivityStatus.values());
        // Load related data for vote/leaderboard management
        if (activity.getTemplateType() == TemplateType.VOTE) {
            model.addAttribute("voteOptions", activityService.getVoteOptions(id));
        }
        if (activity.getTemplateType() == TemplateType.LEADERBOARD) {
            model.addAttribute("leaderboardEntries", activityService.getLeaderboard(id));
        }
        return "admin/activity-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @ModelAttribute ActivityFormDTO form, RedirectAttributes redirect) {
        Activity activity = mapToEntity(form);
        activityService.updateActivity(id, activity);
        redirect.addFlashAttribute("message", "活动更新成功！");
        return "redirect:/admin/activities";
    }

    @PostMapping("/{id}/end")
    public String endActivity(@PathVariable Long id, RedirectAttributes redirect) {
        activityService.endActivity(id);
        redirect.addFlashAttribute("message", "活动已结束！");
        return "redirect:/admin/activities";
    }

    @PostMapping("/{id}/delete")
    public String deleteActivity(@PathVariable Long id, RedirectAttributes redirect) {
        activityService.deleteActivity(id);
        redirect.addFlashAttribute("message", "活动已删除！");
        return "redirect:/admin/activities";
    }

    // === Vote Options Management ===

    @PostMapping("/{id}/vote-options")
    public String addVoteOption(@PathVariable Long id,
                                @RequestParam String optionName,
                                @RequestParam(required = false) String optionImage,
                                @RequestParam(defaultValue = "0") Integer sortOrder,
                                RedirectAttributes redirect) {
        VoteOption option = VoteOption.builder()
                .optionName(optionName)
                .optionImage(optionImage)
                .sortOrder(sortOrder)
                .voteCount(0)
                .build();
        activityService.addVoteOption(id, option);
        redirect.addFlashAttribute("message", "投票选项已添加！");
        return "redirect:/admin/activities/" + id + "/edit";
    }

    @PostMapping("/vote-options/{optionId}/delete")
    public String deleteVoteOption(@PathVariable Long optionId, RedirectAttributes redirect) {
        activityService.deleteVoteOption(optionId);
        redirect.addFlashAttribute("message", "投票选项已删除！");
        return "redirect:/admin/activities";
    }

    // === Leaderboard Management ===

    @PostMapping("/{id}/leaderboard")
    public String addLeaderboardEntry(@PathVariable Long id,
                                      @RequestParam String playerName,
                                      @RequestParam Double score,
                                      @RequestParam Integer rankPosition,
                                      RedirectAttributes redirect) {
        LeaderboardEntry entry = LeaderboardEntry.builder()
                .playerName(playerName)
                .score(score)
                .rankPosition(rankPosition)
                .build();
        activityService.addLeaderboardEntry(id, entry);
        redirect.addFlashAttribute("message", "排行记录已添加！");
        return "redirect:/admin/activities/" + id + "/edit";
    }

    @PostMapping("/leaderboard/{entryId}/delete")
    public String deleteLeaderboardEntry(@PathVariable Long entryId, RedirectAttributes redirect) {
        activityService.deleteLeaderboardEntry(entryId);
        redirect.addFlashAttribute("message", "排行记录已删除！");
        return "redirect:/admin/activities";
    }

    // === Helper Methods ===

    private Activity mapToEntity(ActivityFormDTO form) {
        return Activity.builder()
                .title(form.getTitle())
                .summary(form.getSummary())
                .content(form.getContent())
                .thumbnail(form.getThumbnail())
                .templateType(form.getTemplateType())
                .status(form.getStatus() != null ? form.getStatus() : ActivityStatus.UPCOMING)
                .startDate(form.getStartDate() != null && !form.getStartDate().isEmpty()
                        ? LocalDateTime.parse(form.getStartDate(), FORMATTER) : null)
                .endDate(form.getEndDate() != null && !form.getEndDate().isEmpty()
                        ? LocalDateTime.parse(form.getEndDate(), FORMATTER) : null)
                .configJson(form.getConfigJson())
                .build();
    }

    private ActivityFormDTO mapToDTO(Activity activity) {
        ActivityFormDTO dto = new ActivityFormDTO();
        dto.setId(activity.getId());
        dto.setTitle(activity.getTitle());
        dto.setSummary(activity.getSummary());
        dto.setContent(activity.getContent());
        dto.setThumbnail(activity.getThumbnail());
        dto.setTemplateType(activity.getTemplateType());
        dto.setStatus(activity.getStatus());
        dto.setStartDate(activity.getStartDate() != null
                ? activity.getStartDate().format(FORMATTER) : null);
        dto.setEndDate(activity.getEndDate() != null
                ? activity.getEndDate().format(FORMATTER) : null);
        dto.setConfigJson(activity.getConfigJson());
        return dto;
    }
}
