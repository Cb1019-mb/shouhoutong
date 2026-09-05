package com.sht.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.chat.mapper.CustomerMapper;
import com.sht.governance.IdentityContext;
import com.sht.governance.ToolGuards;
import com.sht.governance.ToolJson;
import com.sht.order.entity.Orders;
import com.sht.refund.RefundEligibilityService;
import com.sht.refund.RefundPlan;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * dry-run:审批前生成操作预览,零写库(红线 B)。
 * 同时承担高危工具提交前的最终业务校验(订单存在性/归属/提交防重/四态资格)。
 * 订单查找与归属校验统一走 ToolGuards。
 */
@Service
public class DryRunService {

    private final ToolGuards toolGuards;
    private final ToolJson toolJson;
    private final CustomerMapper customerMapper;
    private final com.sht.approval.mapper.ApprovalTaskMapper approvalTaskMapper;
    private final RefundEligibilityService refundEligibilityService;
    private final IdentityContext identityContext;
    private final ObjectMapper objectMapper;

    public DryRunService(ToolGuards toolGuards, ToolJson toolJson,
                         CustomerMapper customerMapper,
                         com.sht.approval.mapper.ApprovalTaskMapper approvalTaskMapper,
                         RefundEligibilityService refundEligibilityService,
                         IdentityContext identityContext, ObjectMapper objectMapper) {
        this.toolGuards = toolGuards;
        this.toolJson = toolJson;
        this.customerMapper = customerMapper;
        this.approvalTaskMapper = approvalTaskMapper;
        this.refundEligibilityService = refundEligibilityService;
        this.identityContext = identityContext;
        this.objectMapper = objectMapper;
    }

    /**
     * @return ok=true 时 preview 为操作预览;ok=false 时 toolResultJson 为应直接返回给 LLM 的结构化结果
     */
    public DryRunResult dryRun(String toolName, Long conversationId, Map<String, Object> params) {
        if (!"createRefund".equals(toolName)) {
            return DryRunResult.fail(toolJson.denied("UNPREVIEWABLE", "该工具暂不支持预览"));
        }
        Long customerId = identityContext.resolve(conversationId);
        if (customerId == null) {
            return DryRunResult.fail(toolJson.denied("IDENTITY_MISSING", "无法确认客户身份"));
        }
        String orderNo = String.valueOf(params.getOrDefault("orderNo", ""));
        ToolGuards.GuardResult guard = toolGuards.ownedOrder(conversationId, customerId, orderNo, null);
        if (guard.failed()) {
            return DryRunResult.fail(guard.failureJson());
        }
        Orders order = guard.order();
        // 提交防重:同订单已有 PENDING 审批时拦截,杜绝"幽灵审批"(批准后执行失败的根源)
        Long pending = approvalTaskMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.sht.approval.entity.ApprovalTask>()
                        .eq(com.sht.approval.entity.ApprovalTask::getOrderNo, orderNo)
                        .eq(com.sht.approval.entity.ApprovalTask::getStatus, "PENDING"));
        if (pending > 0) {
            return DryRunResult.fail(toolJson.write(Map.of(
                    "status", "ALREADY_PENDING",
                    "message", "该订单已有一笔退款申请正在等待店主审批,请耐心等待审批结果",
                    "reply_guidance", "请原样向客户转述 message 字段内容,不要添加其他内容")));
        }
        Double amount = params.get("amount") instanceof Number n ? n.doubleValue()
                : order.getAmount().doubleValue();
        if (amount <= 0) {
            return DryRunResult.fail(toolJson.denied("PARAM_INVALID", "退款金额无效"));
        }
        RefundPlan plan = refundEligibilityService.judge(order);
        if ("REJECT".equals(plan.verdict())) {
            return DryRunResult.fail(toolJson.write(Map.of(
                    "status", "REFUND_REJECTED",
                    "rejectReason", plan.rejectReason(),
                    "reply_guidance", "请礼貌告知客户无法退款的原因:" + plan.rejectReason() + "。不要补充任何其他承诺")));
        }
        if ("REJECT_WITH_COUPON".equals(plan.verdict())) {
            return DryRunResult.fail(toolJson.write(Map.of(
                    "status", "REFUND_REJECTED_WITH_COUPON",
                    "rejectReason", plan.rejectReason(),
                    "reply_guidance", "请告知客户无法退款,原因是:" + plan.rejectReason()
                            + "。然后询问客户是否需要 10 元补偿券,仅此一句,不要提及审批")));
        }
        // 组装操作预览:改前/改后一目了然
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("tool", toolName);
        preview.put("orderNo", order.getOrderNo());
        preview.put("product", order.getProductName());
        preview.put("customerName", customerName(order.getCustomerId()));
        preview.put("currentStatus", order.getStatus());
        preview.put("targetStatus", com.sht.order.OrderStatus.REFUNDING.name());
        preview.put("refundType", plan.type());
        preview.put("amount", amount);
        preview.put("reason", String.valueOf(params.getOrDefault("reason", "")));
        preview.put("planHint", plan.hint());
        return DryRunResult.ok(preview);
    }

    private String customerName(Long customerId) {
        var c = customerMapper.selectById(customerId);
        return c == null ? ("客户" + customerId) : c.getName();
    }

    public record DryRunResult(boolean ok, String toolResultJson, Map<String, Object> preview) {
        public static DryRunResult fail(String json) {
            return new DryRunResult(false, json, null);
        }

        public static DryRunResult ok(Map<String, Object> preview) {
            return new DryRunResult(true, null, preview);
        }
    }
}
