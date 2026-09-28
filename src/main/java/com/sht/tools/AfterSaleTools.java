package com.sht.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.audit.AuditService;
import com.sht.governance.Governed;
import com.sht.governance.IdentityContext;
import com.sht.governance.PolicyEngine;
import com.sht.governance.ToolGuards;
import com.sht.governance.ToolJson;
import com.sht.order.entity.Orders;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 售后工具集。所有方法经 @Governed 进入治理切面;
 * 身份由服务端绑定(ToolContext 携带 conversationId → IdentityContext 解析),
 * LLM 只能传业务参数,无法伪造客户身份。
 * 订单存在性/归属校验统一走 ToolGuards(单一事实源)。
 */
@Component
public class AfterSaleTools {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final IdentityContext identityContext;
    private final PolicyEngine policyEngine;
    private final AuditService auditService;
    private final ToolJson toolJson;
    private final ToolGuards toolGuards;

    public AfterSaleTools(IdentityContext identityContext,
                          PolicyEngine policyEngine,
                          AuditService auditService,
                          ToolJson toolJson,
                          ToolGuards toolGuards) {
        this.identityContext = identityContext;
        this.policyEngine = policyEngine;
        this.auditService = auditService;
        this.toolJson = toolJson;
        this.toolGuards = toolGuards;
    }

    @Tool(description = "查询订单详情。当买家询问订单状态、订单内容时必须调用此工具,禁止编造订单信息。参数 orderNo 为买家消息中出现的订单号。")
    @Governed("queryOrder")
    public String queryOrder(@ToolParam(description = "订单号,如 2025001") String orderNo,
                             ToolContext toolContext) {
        Long customerId = currentCustomer(toolContext);
        if (customerId == null) {
            return toolJson.denied("IDENTITY_MISSING", "无法确认客户身份");
        }
        ToolGuards.GuardResult guard = toolGuards.ownedOrder(
                conversationId(toolContext), customerId, orderNo, "queryOrder[identity]");
        if (guard.failed()) {
            return guard.failureJson();
        }
        Orders order = guard.order();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("orderNo", order.getOrderNo());
        out.put("product", order.getProductName());
        out.put("amount", order.getAmount());
        out.put("orderStatus", order.getStatus());
        out.put("createdAt", order.getCreatedAt() == null ? "" : order.getCreatedAt().format(TS));
        return toolJson.write(out);
    }

    // confirm 占位参数必填:Spring AI 1.0 对零参工具的空 toolInput 会断言失败(与 sendCoupon 同款规避)
    @Tool(description = "查询当前买家名下的全部订单列表(订单号/商品/金额/状态)。当买家想知道自己有哪些订单、没有提供订单号、或对订单号拿不准时调用;列出后请买家确认要咨询或退款的那一笔。禁止编造订单。")
    @Governed("queryMyOrders")
    public String queryMyOrders(@ToolParam(description = "固定填 list") String confirm,
                                ToolContext toolContext) {
        Long customerId = currentCustomer(toolContext);
        if (customerId == null) {
            return toolJson.denied("IDENTITY_MISSING", "无法确认客户身份");
        }
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Orders order : toolGuards.ownedOrders(customerId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderNo", order.getOrderNo());
            item.put("product", order.getProductName());
            item.put("amount", order.getAmount());
            item.put("orderStatus", order.getStatus());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("count", items.size());
        out.put("orders", items);
        return toolJson.write(out);
    }

    @Tool(description = "查询物流进度。当买家询问'到哪了''物流'时必须调用此工具。参数 orderNo 为买家消息中出现的订单号。")
    @Governed("queryLogistics")
    public String queryLogistics(@ToolParam(description = "订单号") String orderNo,
                                 ToolContext toolContext) {
        Long customerId = currentCustomer(toolContext);
        if (customerId == null) {
            return toolJson.denied("IDENTITY_MISSING", "无法确认客户身份");
        }
        ToolGuards.GuardResult guard = toolGuards.ownedOrder(
                conversationId(toolContext), customerId, orderNo, null);
        if (guard.failed()) {
            return guard.failureJson();
        }
        Orders order = guard.order();
        com.sht.order.OrderStatus status = com.sht.order.OrderStatus.parse(order.getStatus());
        if (status == null) {
            status = com.sht.order.OrderStatus.CLOSED;
        }
        String statusDesc = switch (status) {
            case PAID -> "商家已收款,仓库备货中,暂无物流信息";
            case SHIPPED -> "运输中:快件已从深圳转运中心发出,正在派送途中";
            case SIGNED -> "已签收:快件已送达并由收件人签收"
                    + (order.getSignedAt() == null ? "" : "(签收时间 " + order.getSignedAt().format(TS) + ")");
            case REFUNDING, REFUNDED -> "该订单已进入退款流程,物流已终止";
            default -> "该订单已关闭,无物流信息";
        };
        return toolJson.write(Map.of(
                "status", "OK",
                "orderNo", order.getOrderNo(),
                "orderStatus", order.getStatus(),
                "carrier", "顺丰速运",
                "trackingNo", "SF" + order.getOrderNo(),
                "logistics", statusDesc));
    }

    @Tool(description = "向当前客户发放一张补偿券(每客户每日限 1 张)。仅在退款被拒绝且客户情绪需要安抚、或政策允许补偿的场景下调用。参数 confirm 固定填 customer_care。")
    @Governed("sendCoupon")
    public String sendCoupon(@ToolParam(description = "固定填 customer_care") String confirm,
                             ToolContext toolContext) {
        Long customerId = currentCustomer(toolContext);
        if (customerId == null) {
            return toolJson.denied("IDENTITY_MISSING", "无法确认客户身份");
        }
        if (!policyEngine.tryAcquireCouponQuota(customerId)) {
            auditService.write(conversationId(toolContext), "sendCoupon[quota]", "{}",
                    "超出每客户每日 " + "领券限额", "MID", "DENY", null, 0);
            auditService.hit("COUPON_QUOTA_EXCEEDED");
            return toolJson.denied("COUPON_QUOTA_EXCEEDED", "该客户今日已领取过补偿券,超出限额");
        }
        String code = "CQ" + ThreadLocalRandom.current().nextInt(100000, 999999);
        auditService.hit("coupon_granted:" + customerId);
        return toolJson.write(Map.of(
                "status", "OK",
                "couponCode", code,
                "amount", 10,
                "message", "已发放 10 元无门槛补偿券"));
    }

    /**
     * 退款申请为高风险工具(策略 action=APPROVAL),治理切面会拦截并转
     * ApprovalFlowService.submit → DryRunService(生成预览、挂审批),本方法体不可达。
     * 签名保留仅供 Spring AI 生成工具 schema;真实业务校验在 DryRunService。
     */
    @Tool(description = "创建退款申请(高风险)。当买家明确要求退款/退货退款时必须先调用此工具校验资格。需提供订单号和退款原因;买家未说明金额时不要编造,省略 amount 即可。")
    @Governed("createRefund")
    public String createRefund(@ToolParam(description = "订单号") String orderNo,
                               @ToolParam(description = "退款原因") String reason,
                               @ToolParam(description = "退款金额(元);买家未说明时省略,默认全额", required = false)
                               Double amount,
                               ToolContext toolContext) {
        return "{\"status\":\"HANDLED_BY_GOVERNANCE\"}";
    }

    private Long currentCustomer(ToolContext toolContext) {
        return identityContext.resolve(conversationId(toolContext));
    }

    private Long conversationId(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object cid = toolContext.getContext().get("conversationId");
        return cid instanceof Number n ? n.longValue() : null;
    }
}
