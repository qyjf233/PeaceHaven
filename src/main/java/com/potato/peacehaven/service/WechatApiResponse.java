package com.potato.peacehaven.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * WechatApi 统一响应体
 * <p>
 * 所有 WechatApi 接口返回格式一致：
 * <pre>
 * {
 *   "ret": 200,
 *   "msg": "success",
 *   "data": { ... } | true | false | ...
 * }
 * </pre>
 * <p>
 * 注意：data 字段类型不固定，大多数接口是 Map，但 checkOnline 等接口返回 boolean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WechatApiResponse {

    /** 状态码：200=成功，401=Token无效，403=设备异常，429=频率限制，500=服务端错误 */
    private int ret;

    /** 描述信息 */
    private String msg;

    /** 响应数据（可能是 Map、Boolean 或其他类型） */
    private Object data;

    /** 是否成功 */
    public boolean isSuccess() {
        return ret == 200;
    }

    /** 将 data 作为 Map 返回（若不是 Map 则返回 null） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        if (data instanceof Map) return (Map<String, Object>) data;
        return null;
    }

    /** 从 data(Map) 中取字符串字段 */
    public String getString(String key) {
        Map<String, Object> m = getDataAsMap();
        if (m == null) return null;
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    /** 从 data(Map) 中取整数字段 */
    public Integer getInt(String key) {
        Map<String, Object> m = getDataAsMap();
        if (m == null) return null;
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) return Integer.parseInt((String) v);
        return null;
    }

    @Override
    public String toString() {
        return "WechatApiResponse{ret=" + ret + ", msg='" + msg + "', data=" + data + '}';
    }
}
