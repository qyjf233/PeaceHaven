package com.potato.peacehaven.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * CSRF Token 生成与校验服务
 * 每个 Session 持有一个 Token，所有状态变更请求必须携带并校验
 */
@Service
public class CsrfService {

    private static final String CSRF_SESSION_KEY = "_csrf_token";
    private static final String CSRF_PARAM_NAME = "_csrf";
    private static final int TOKEN_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 获取或生成当前 Session 的 CSRF Token
     */
    public String getOrCreateToken(HttpSession session) {
        String token = (String) session.getAttribute(CSRF_SESSION_KEY);
        if (token == null) {
            token = generateToken();
            session.setAttribute(CSRF_SESSION_KEY, token);
        }
        return token;
    }

    /**
     * 校验请求中的 CSRF Token 是否与 Session 中一致
     */
    public boolean validateToken(HttpSession session, String requestToken) {
        if (session == null || requestToken == null || requestToken.isBlank()) {
            return false;
        }
        String sessionToken = (String) session.getAttribute(CSRF_SESSION_KEY);
        return sessionToken != null && sessionToken.equals(requestToken);
    }

    /**
     * CSRF 表单参数名
     */
    public static String getParamName() {
        return CSRF_PARAM_NAME;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
