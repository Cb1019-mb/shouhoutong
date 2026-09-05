package com.sht.governance;

/**
 * 策略裁决动作:
 * ALLOW 直接放行;APPROVAL 走人工审批(W3 接审批闭环);DENY 一律拒绝。
 */
public enum PolicyAction {
    ALLOW, APPROVAL, DENY
}
