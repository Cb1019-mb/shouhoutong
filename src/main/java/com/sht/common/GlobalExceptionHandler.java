package com.sht.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常兜底:参数/业务错误返回 400 携带原因,未预期异常返回 500 且堆栈不外泄。
 * 错误体形状与既有接口一致({"error": ...})。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(Map.of("error", "系统开小差了,请稍后重试"));
    }
}
