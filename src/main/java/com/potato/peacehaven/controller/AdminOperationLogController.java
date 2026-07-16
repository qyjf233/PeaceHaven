package com.potato.peacehaven.controller;

import com.potato.peacehaven.entity.AdminOperationLog;
import com.potato.peacehaven.service.AdminOperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员操作日志查看页
 */
@Controller
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
public class AdminOperationLogController {

    private final AdminOperationLogService operationLogService;

    private static final List<String> MODULES = List.of(
            "活动管理", "用户管理", "营地成员", "营地事务", "福利系统", "作品审核", "机器人配置"
    );

    @GetMapping
    public String list(@RequestParam(required = false) String module,
                       @RequestParam(required = false) String operator,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<AdminOperationLog> logPage = operationLogService.getLogs(module, operator, page, 30);

        model.addAttribute("logPage", logPage);
        model.addAttribute("currentPage", page);
        model.addAttribute("modules", MODULES);
        model.addAttribute("selectedModule", module);
        model.addAttribute("selectedOperator", operator);
        return "admin/operation-logs";
    }
}
