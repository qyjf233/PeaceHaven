package com.potato.peacehaven.service;

import com.potato.peacehaven.entity.AdminOperationLog;
import com.potato.peacehaven.entity.User;
import com.potato.peacehaven.repository.AdminOperationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOperationLogService {

    private final AdminOperationLogRepository operationLogRepository;
    private final UserService userService;

    /**
     * 记录管理员操作日志
     *
     * @param module  操作模块（活动管理、营地成员、营地事务、福利系统、用户管理、作品审核）
     * @param action  操作动作（新增、修改、删除 等）
     * @param detail  操作详情
     * @param request HTTP 请求（用于获取 session 和 IP）
     */
    public void record(String module, String action, String detail, HttpServletRequest request) {
        String operator = "未知";
        HttpSession session = request.getSession(false);
        if (session != null) {
            User user = userService.getCurrentUser(session);
            if (user != null) {
                operator = user.getNickname() != null ? user.getNickname() : user.getPhone();
            }
        }

        String ip = getClientIp(request);

        AdminOperationLog logEntry = AdminOperationLog.builder()
                .operator(operator)
                .module(module)
                .action(action)
                .detail(detail != null && detail.length() > 490 ? detail.substring(0, 490) : detail)
                .ip(ip)
                .build();

        try {
            operationLogRepository.save(logEntry);
        } catch (Exception e) {
            // 日志记录失败不应影响主业务
            log.error("操作日志记录失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 分页查询操作日志
     */
    public Page<AdminOperationLog> getLogs(String module, String operator, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);

        boolean hasModule = module != null && !module.isEmpty();
        boolean hasOperator = operator != null && !operator.isEmpty();

        if (hasModule && hasOperator) {
            return operationLogRepository.findByModuleAndOperatorOrderByCreatedAtDesc(module, operator, pageRequest);
        } else if (hasModule) {
            return operationLogRepository.findByModuleOrderByCreatedAtDesc(module, pageRequest);
        } else if (hasOperator) {
            return operationLogRepository.findByOperatorOrderByCreatedAtDesc(operator, pageRequest);
        } else {
            return operationLogRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        }
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
