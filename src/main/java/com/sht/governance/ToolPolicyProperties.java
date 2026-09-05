package com.sht.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * tools-policy.yaml 绑定:工具风险分级表。
 * 未在此注册的工具一律 DENY(default-deny)。
 * 业务数值参数(强制人工阈值/券限额/防重窗口/审批过期)已迁移至运行时设置
 * (SettingKey → app_setting 表,/settings 页可调),此处不再重复配置。
 */
@ConfigurationProperties(prefix = "policy")
public class ToolPolicyProperties {

    private Map<String, ToolRule> tools = new HashMap<>();

    public Map<String, ToolRule> getTools() {
        return tools;
    }

    public void setTools(Map<String, ToolRule> tools) {
        this.tools = tools;
    }

    public record ToolRule(RiskLevel risk, PolicyAction action) {
    }
}
