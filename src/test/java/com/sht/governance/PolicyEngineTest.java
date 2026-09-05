package com.sht.governance;

import com.sht.audit.AuditService;
import com.sht.order.entity.Orders;
import com.sht.order.mapper.OrdersMapper;
import com.sht.refund.mapper.RefundRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略引擎单测(default-deny 语义与 guard-rails):
 * 无规则拒绝 / 兜底拒绝 / 异常 fail-closed / 超500强制人工 / 金额超实付 / 24h重复 / 低风险放行。
 */
class PolicyEngineTest {

    private OrdersMapper ordersMapper;
    private RefundRequestMapper refundRequestMapper;
    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        ordersMapper = mock(OrdersMapper.class);
        refundRequestMapper = mock(RefundRequestMapper.class);
        AuditService auditService = mock(AuditService.class);
        // 设置服务:空库 → 全部走默认值
        com.sht.settings.SettingsService settings =
                new com.sht.settings.SettingsService(mock(com.sht.settings.mapper.AppSettingMapper.class));
        ToolPolicyProperties props = new ToolPolicyProperties();
        props.getTools().put("queryOrder", new ToolPolicyProperties.ToolRule(RiskLevel.LOW, PolicyAction.ALLOW));
        props.getTools().put("createRefund", new ToolPolicyProperties.ToolRule(RiskLevel.HIGH, PolicyAction.APPROVAL));
        engine = new PolicyEngine(props, ordersMapper, refundRequestMapper, auditService, settings);
    }

    private Orders order(double amount) {
        Orders o = new Orders();
        o.setId(1L);
        o.setOrderNo("2025001");
        o.setAmount(BigDecimal.valueOf(amount));
        o.setStatus("PAID");
        return o;
    }

    @Test
    @DisplayName("1. 未注册工具 → default-deny 拒绝")
    void unregisteredToolDenied() {
        PolicyDecision d = engine.decide(1L, "notRegistered", Map.of());
        assertThat(d.action()).isEqualTo(PolicyAction.DENY);
        assertThat(d.ruleKey()).isEqualTo("DEFAULT_DENY");
    }

    @Test
    @DisplayName("2. 空策略表 → 兜底拒绝(fail-closed)")
    void emptyPolicyDenied() {
        com.sht.settings.SettingsService settings =
                new com.sht.settings.SettingsService(mock(com.sht.settings.mapper.AppSettingMapper.class));
        PolicyEngine empty = new PolicyEngine(new ToolPolicyProperties(), ordersMapper,
                refundRequestMapper, Mockito.mock(AuditService.class), settings);
        PolicyDecision d = empty.decide(1L, "queryOrder", Map.of());
        assertThat(d.denied()).isTrue();
    }

    @Test
    @DisplayName("3. 引擎依赖异常 → fallback 拒绝")
    void exceptionFailClosed() {
        when(ordersMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        PolicyDecision d = engine.decide(1L, "createRefund",
                Map.of("orderNo", "2025001", "amount", 100.0));
        assertThat(d.action()).isEqualTo(PolicyAction.DENY);
        assertThat(d.ruleKey()).isEqualTo("ENGINE_FALLBACK_DENY");
    }

    @Test
    @DisplayName("4. 低风险工具直接放行")
    void lowRiskAllowed() {
        PolicyDecision d = engine.decide(1L, "queryOrder", Map.of("orderNo", "2025001"));
        assertThat(d.action()).isEqualTo(PolicyAction.ALLOW);
        assertThat(d.risk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("5. 退款金额超过实付 → 拒绝")
    void amountExceedsPaidDenied() {
        when(ordersMapper.selectOne(any())).thenReturn(order(199.0));
        when(refundRequestMapper.selectCount(any())).thenReturn(0L);
        PolicyDecision d = engine.decide(1L, "createRefund",
                Map.of("orderNo", "2025001", "amount", 300.0));
        assertThat(d.action()).isEqualTo(PolicyAction.DENY);
        assertThat(d.ruleKey()).isEqualTo("AMOUNT_EXCEEDS_PAID");
    }

    @Test
    @DisplayName("6. 单笔超 ¥500 → 强制人工审批(即使策略是低风险)")
    void forceApprovalOver500() {
        when(ordersMapper.selectOne(any())).thenReturn(order(1000.0));
        when(refundRequestMapper.selectCount(any())).thenReturn(0L);
        PolicyDecision d = engine.decide(1L, "createRefund",
                Map.of("orderNo", "2025001", "amount", 600.0));
        assertThat(d.action()).isEqualTo(PolicyAction.APPROVAL);
        assertThat(d.ruleKey()).isEqualTo("FORCE_APPROVAL_OVER_500");
    }

    @Test
    @DisplayName("7. 24h 内重复退款 → 拒绝")
    void duplicateRefundDenied() {
        when(ordersMapper.selectOne(any())).thenReturn(order(199.0));
        when(refundRequestMapper.selectCount(any())).thenReturn(1L);
        PolicyDecision d = engine.decide(1L, "createRefund",
                Map.of("orderNo", "2025001", "amount", 100.0));
        assertThat(d.action()).isEqualTo(PolicyAction.DENY);
        assertThat(d.ruleKey()).isEqualTo("REFUND_DUPLICATE_24H");
    }

    @Test
    @DisplayName("8. 高风险正常退款 → APPROVAL 动作")
    void highRiskGoesApproval() {
        when(ordersMapper.selectOne(any())).thenReturn(order(329.0));
        when(refundRequestMapper.selectCount(any())).thenReturn(0L);
        PolicyDecision d = engine.decide(1L, "createRefund",
                Map.of("orderNo", "2025001", "amount", 329.0));
        assertThat(d.action()).isEqualTo(PolicyAction.APPROVAL);
        assertThat(d.ruleKey()).isEqualTo("HIGH_RISK_APPROVAL");
    }
}
