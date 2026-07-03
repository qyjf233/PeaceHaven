package com.potato.peacehaven.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 捕获所有未处理的异常并记录详细日志，方便在 SLS 中按接口名搜索定位
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 文件上传超过大小限制（413）
     * 这是用户投稿时最可能遇到的错误之一
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                                    HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        log.error("[EXCEPTION] {} {} | 413 文件上传超限 | {}", method, uri, ex.getMessage());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", "上传文件超过大小限制，请压缩后重试");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(result);
    }

    /**
     * 请求方法不支持（405）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                         HttpServletRequest request) {
        String uri = request.getRequestURI();
        log.warn("[EXCEPTION] {} {} | 405 方法不支持 | 允许的: {}", request.getMethod(), uri, ex.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                Map.of("success", false, "message", "不支持的请求方法: " + request.getMethod()));
    }

    /**
     * 缺少必填参数（400）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex,
                                                                   HttpServletRequest request) {
        String uri = request.getRequestURI();
        log.warn("[EXCEPTION] {} {} | 400 缺少参数: {} ({})", request.getMethod(), uri, ex.getParameterName(), ex.getParameterType());
        return ResponseEntity.badRequest().body(
                Map.of("success", false, "message", "缺少必填参数: " + ex.getParameterName()));
    }

    /**
     * 参数类型不匹配（400）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                   HttpServletRequest request) {
        String uri = request.getRequestURI();
        log.warn("[EXCEPTION] {} {} | 400 参数类型错误: {}={}", request.getMethod(), uri, ex.getName(), ex.getValue());
        return ResponseEntity.badRequest().body(
                Map.of("success", false, "message", "参数格式错误: " + ex.getName()));
    }

    /**
     * 静态资源不存在（404）- 不记录日志，减少噪音
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * 兜底：捕获所有其他异常（500）
     * 这是最重要的异常处理，确保任何未预期的错误都被记录
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        String queryString = request.getQueryString();
        String fullUri = queryString != null ? uri + "?" + queryString : uri;

        log.error("[EXCEPTION] {} {} | 500 服务器内部错误 | {}: {}", method, fullUri, ex.getClass().getSimpleName(), ex.getMessage(), ex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", "服务器内部错误，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }
}
