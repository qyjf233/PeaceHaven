package com.potato.peacehaven.config;

import com.potato.peacehaven.service.CsrfService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局 CSRF Token 注入
 * 自动将 CSRF Token 添加到所有 Controller 的 Model 中
 * 模板中通过 ${_csrf} 引用
 */
@ControllerAdvice
@RequiredArgsConstructor
public class CsrfModelAdvice {

    private final CsrfService csrfService;

    @ModelAttribute("_csrf")
    public String csrfToken(HttpSession session) {
        return csrfService.getOrCreateToken(session);
    }
}
