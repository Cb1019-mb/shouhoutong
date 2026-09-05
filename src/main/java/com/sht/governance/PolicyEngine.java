package com.sht.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.audit.AuditService;
import com.sht.order.entity.Orders;
import com.sht.order.mapper.OrdersMapper;
import com.sht.refund.entity.RefundRequest;
import com.sht.refund.mapper.RefundRequestMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 策略引擎:所有工具调用的裁决入口。
 * ① 未注册工具 → DENY(default-deny);② 参数感知防线(guard-rails)优先;
 * ③ 工具级 YAML 策略兜底;④ 任何异常 → fail-closed 拒绝。
 *
 * W2:APPROVAL 动作暂以"需人工审批"话术返回(W3 接审批闭环真实执行)。
 */
@Service
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final ToolPolicyProperties props;
    private final OrdersMapper ordersMapper;
    private final RefundRequestMapper refundRequestMapper;
    private final AuditService auditService;
    private final com.sht.settings.SettingsService settingsService;

    public PolicyEngine(ToolPolicyProperties props, OrdersMapper ordersMapper,
                        RefundRequestMapper refundRequestMapper, AuditService auditService,
                        com.sht.settings.SettingsService settingsService) {
        this.props = props;
        this.ordersMapper = ordersMapper;
        this.refundRequestMapper = refundRequestMapper;
        this.auditService = auditService;
        this.settingsService = settingsService;
    }

    /**
     * @param toolName 工具名(未注册 → 拒绝)
     * @param params   工具入参(用于参数感知防线)
     */
    public PolicyDecision decide(Long conversationId, String toolName, Map<String, Object> params) {
        try {
            ToolPolicyProperties.ToolRule rule = props.getTools().get(toolName);
            if (rule == null) {
                return PolicyDecision.deny("DEFAULT_DENY", "未注册的工具调用");
            }
            // guard-rails:参数感知规则优先于工具级策略
            if ("createRefund".equals(toolName)) {
                PolicyDecision guard = refundGuardRails(params);
                if (guard != null) {
                    return guard;
                }
            }
            return switch (rule.action()) {
                case ALLOW -> PolicyDecision.allow(rule.risk());
                case APPROVAL -> new PolicyDecision(PolicyAction.APPROVAL, rule.risk(),
                        "HIGH_RISK_APPROVAL", "高风险操作,需店主审批");
                case DENY -> PolicyDecision.deny("POLICY_DENY", "该工具被策略禁止");
            };
        } catch (Exception e) {
            log.error("策略引擎异常,fail-closed 拒绝 tool={}", toolName, e);
            return PolicyDecision.deny("ENGINE_FALLBACK_DENY", "策略引擎异常,默认拒绝");
        }
    }

    /** 退款防线:金额≤实付 / 超500强制人工 / 24h 一次 */
    private PolicyDecision refundGuardRails(Map<String, Object> params) {
        String orderNo = str(params.get("orderNo"));
        if (orderNo == null || orderNo.isBlank()) {
            return PolicyDecision.deny("PARAM_MISSING", "缺少订单号");
        }
        Orders order = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, orderNo));
        if (order == null) {
            return null; // 订单不存在交给工具实现返回 NOT_FOUND,不算策略拒绝
        }
        // 超 ¥500 强制人工(运行时设置优先,yml 为兜底;即使工具是低风险也逃不掉)
        Double amount = params.get("amount") instanceof Number n ? n.doubleValue() : null;
        if (amount != null && amount > order.getAmount().doubleValue()) {
            return PolicyDecision.deny("AMOUNT_EXCEEDS_PAID", "退款金额超过订单实付金额");
        }
        double forceOver = settingsService.getDouble(
                com.sht.settings.SettingKey.RULE_FORCE_APPROVAL_OVER);
        if (amount != null && amount > forceOver) {
            return new PolicyDecision(PolicyAction.APPROVAL, RiskLevel.HIGH,
                    "FORCE_APPROVAL_OVER_500", "单笔超过 " + (int) forceOver + " 元,强制人工审批");
        }
        // 同一订单 24h 内仅一次退款申请(窗口运行时可调)
        int windowHours = settingsService.getInt(
                com.sht.settings.SettingKey.RULE_REFUND_REPEAT_HOURS);
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        Long recent = refundRequestMapper.selectCount(new LambdaQueryWrapper<RefundRequest>()
                .eq(RefundRequest::getOrderId, order.getId())
                .ge(RefundRequest::getCreatedAt, since));
        if (recent > 0) {
            return PolicyDecision.deny("REFUND_DUPLICATE_24H",
                    "该订单 " + windowHours + " 小时内已提交过退款申请");
        }
        return null;
    }

    /** 中风险限额:券每客户每日 N 张(走 policy_hit_stat 计数,限额运行时可调) */
    public boolean tryAcquireCouponQuota(Long customerId) {
        return auditService.tryIncrementDaily("coupon_daily:" + customerId,
                settingsService.getInt(com.sht.settings.SettingKey.RULE_COUPON_DAILY_LIMIT));
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }
}
