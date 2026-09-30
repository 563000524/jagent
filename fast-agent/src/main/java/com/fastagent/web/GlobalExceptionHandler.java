package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.service.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口。
 *
 * <p>关键点：受理阶段的校验失败（未配 Key、消息为空）必须在 SSE 开始之前
 * 以 400 + JSON 返回，前端才能 catch 到可读原因；否则前端只能拿到一个
 * 看似成功的空流，表现为「发送后没有任何反应」。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        AppLog.warn("业务校验失败(%d): %s", e.getStatus(), e.getMessage());
        return build(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        AppLog.error(e, "接口异常");
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return build(500, "内部错误: " + msg);
    }

    private static ResponseEntity<Map<String, Object>> build(int status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }
}
