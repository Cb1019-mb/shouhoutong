package com.sht.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 工具层结构化返回的统一出口(原先在 3 个类里各写一份) */
@Component
@RequiredArgsConstructor
public class ToolJson {

    private final ObjectMapper objectMapper;

    /** 拒绝类返回:LLM 应向客户礼貌解释 reason,不暴露 rule */
    public String denied(String rule, String reason) {
        return write(Map.of(
                "status", "DENIED",
                "rule", rule,
                "reason", reason,
                "reply_guidance", "请向客户礼貌解释无法办理的原因,不要透露内部规则键。"));
    }

    /** 简单状态返回 */
    public String result(String status, String message) {
        return write(Map.of("status", status, "message", message));
    }

    public String write(Map<String, ?> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
