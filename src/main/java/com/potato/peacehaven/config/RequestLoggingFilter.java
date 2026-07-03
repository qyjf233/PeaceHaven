package com.potato.peacehaven.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局 HTTP 请求/响应日志过滤器
 * 记录每个 API 请求的完整信息，方便在 SLS 中按接口名搜索定位问题
 *
 * 日志格式示例：
 * [HTTP] POST /api/contest/submit | status=200 | 1523ms | req={title=xxx,description=yyy} | resp={"success":true}
 * [HTTP] GET /api/contest/works | status=200 | 45ms | resp={"works":[...]}
 */
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** 响应体日志最大长度（防止超大 JSON 刷屏） */
    private static final int MAX_BODY_LOG_LENGTH = 2000;

    /** 请求体日志最大长度 */
    private static final int MAX_REQ_BODY_LOG_LENGTH = 1000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        // 包装请求/响应以便重复读取 body
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, 1024 * 1024);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        String method = request.getMethod();
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        String fullUri = queryString != null ? uri + "?" + queryString : uri;
        String contentType = request.getContentType();
        boolean isJson = isJsonContentType(contentType);
        boolean isMultipartReq = isMultipart(contentType);

        // 提取请求参数（表单参数）
        String reqParams = extractRequestParams(request);

        try {
            // 先放行请求，让 Controller 正常处理
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            try {
                long duration = System.currentTimeMillis() - startTime;
                int status = wrappedResponse.getStatus();

                // 从缓存中提取请求体（在 Controller 处理完之后读，不影响请求流）
                String reqBody = "";
                if (isJson) {
                    reqBody = extractRequestBodyFromCache(wrappedRequest);
                } else if (isMultipartReq) {
                    reqBody = extractMultipartInfo(request);
                }

                // 提取响应体
                String respBody = extractResponseBody(wrappedResponse);

                // 构建日志
                StringBuilder logMsg = new StringBuilder();
                logMsg.append("[HTTP] ").append(method).append(" ").append(fullUri);
                logMsg.append(" | status=").append(status);
                logMsg.append(" | ").append(duration).append("ms");

                if (!reqParams.isEmpty()) {
                    logMsg.append(" | params={").append(reqParams).append("}");
                }
                if (!reqBody.isEmpty()) {
                    logMsg.append(" | req=").append(reqBody);
                }
                if (!respBody.isEmpty()) {
                    logMsg.append(" | resp=").append(respBody);
                }

                // 根据状态码选择日志级别
                if (status >= 500) {
                    log.error(logMsg.toString());
                } else if (status >= 400) {
                    log.warn(logMsg.toString());
                } else {
                    log.info(logMsg.toString());
                }

                // 将响应体写回原始响应
                wrappedResponse.copyBodyToResponse();
            } catch (Exception e) {
                log.warn("[HTTP] 日志记录异常: {}", e.getMessage());
                wrappedResponse.copyBodyToResponse();
            }
        }
    }

    /**
     * 跳过静态资源，只记录 API 和管理后台请求
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/fonts/")
                || path.equals("/favicon.ico")
                || path.endsWith(".png")
                || path.endsWith(".jpg")
                || path.endsWith(".jpeg")
                || path.endsWith(".gif")
                || path.endsWith(".webp")
                || path.endsWith(".svg")
                || path.endsWith(".woff")
                || path.endsWith(".woff2")
                || path.endsWith(".ttf")
                || path.endsWith(".ico");
    }

    /**
     * 提取表单请求参数
     */
    private String extractRequestParams(HttpServletRequest request) {
        Map<String, String[]> paramMap = request.getParameterMap();
        if (paramMap == null || paramMap.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
            if (count > 0) sb.append(", ");
            String key = entry.getKey();
            // 脱敏：隐藏密码和验证码
            if (isSensitiveKey(key)) {
                sb.append(key).append("=***");
            } else {
                String[] values = entry.getValue();
                if (values.length == 1) {
                    sb.append(key).append("=").append(truncate(values[0], 200));
                } else {
                    sb.append(key).append("=[").append(String.join(",", values)).append("]");
                }
            }
            count++;
            if (count >= 20) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 从缓存中提取 JSON 请求体（在 Controller 处理完请求后调用）
     * ContentCachingRequestWrapper 会在 Controller 读取 body 时自动缓存，
     * 我们直接从缓存拿即可，不影响请求流
     */
    private String extractRequestBodyFromCache(ContentCachingRequestWrapper request) {
        try {
            byte[] buf = request.getContentAsByteArray();
            if (buf.length == 0) return "";
            String body = new String(buf, StandardCharsets.UTF_8);
            // 脱敏
            body = sanitizeJson(body);
            return truncate(body, MAX_REQ_BODY_LOG_LENGTH);
        } catch (Exception e) {
            return "[读取失败]";
        }
    }

    /**
     * 提取文件上传信息（不记录二进制内容）
     */
    private String extractMultipartInfo(HttpServletRequest request) {
        try {
            String contentType = request.getContentType();
            if (contentType != null && contentType.contains("multipart/form-data")) {
                // 提取非文件参数
                Map<String, String[]> paramMap = request.getParameterMap();
                StringBuilder sb = new StringBuilder("[multipart");
                if (paramMap != null && !paramMap.isEmpty()) {
                    sb.append(" params={");
                    int count = 0;
                    for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
                        if (count > 0) sb.append(", ");
                        String key = entry.getKey();
                        if (isSensitiveKey(key)) {
                            sb.append(key).append("=***");
                        } else {
                            sb.append(key).append("=").append(truncate(entry.getValue()[0], 100));
                        }
                        count++;
                    }
                    sb.append("}");
                }
                sb.append("]");
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return "[multipart]";
    }

    /**
     * 提取响应体
     */
    private String extractResponseBody(ContentCachingResponseWrapper response) {
        try {
            byte[] buf = response.getContentAsByteArray();
            if (buf.length == 0) return "";

            String contentType = response.getContentType();
            // 只记录 JSON 响应，跳过 HTML 页面
            if (contentType != null && !contentType.contains("json")) {
                return "";
            }

            String body = new String(buf, StandardCharsets.UTF_8);
            return truncate(body, MAX_BODY_LOG_LENGTH);
        } catch (Exception e) {
            return "[读取失败]";
        }
    }

    /** 判断是否为 JSON 内容类型 */
    private boolean isJsonContentType(String contentType) {
        return contentType != null && (contentType.contains("json") || contentType.contains("x-www-form-urlencoded"));
    }

    /** 判断是否为文件上传 */
    private boolean isMultipart(String contentType) {
        return contentType != null && contentType.contains("multipart/form-data");
    }

    /** 判断是否为敏感字段 */
    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.contains("code")
                || lower.contains("csrf") || lower.contains("agreed");
    }

    /** JSON 脱敏：隐藏敏感字段值 */
    private String sanitizeJson(String json) {
        if (json == null) return "";
        // 简单替换常见敏感字段的值
        return json.replaceAll("\"(password|secret|token|code|agreed)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"***\"");
    }

    /** 截断字符串 */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...(truncated)";
    }
}
