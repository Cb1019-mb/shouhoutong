package com.sht.governance;

/**
 * 策略引擎裁决结果。
 * ruleKey 用于看板计数(policy_hit_stat),如 "DEFAULT_DENY"、"FORCE_APPROVAL_OVER_500"。
 */
public record PolicyDecision(PolicyAction action, RiskLevel risk, String ruleKey, String reason) {

    public static PolicyDecision allow(RiskLevel risk) {
        return new PolicyDecision(PolicyAction.ALLOW, risk, "ALLOWED", null);
    }

    public static PolicyDecision deny(String ruleKey, String reason) {
        return new PolicyDecision(PolicyAction.DENY, RiskLevel.HIGH, ruleKey, reason);
    }

    public boolean denied() {
        return action == PolicyAction.DENY;
    }
}
