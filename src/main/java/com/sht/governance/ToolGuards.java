package com.sht.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.audit.AuditService;
import com.sht.order.entity.Orders;
import com.sht.order.mapper.OrdersMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工具前置守卫:订单存在性 + 归属校验的单一事实源。
 * 失败时按原各处行为落审计,调用方只需把 GuardResult.failureJson 原样返回给 LLM。
 */
@Component
@RequiredArgsConstructor
public class ToolGuards {

    private final OrdersMapper ordersMapper;
    private final AuditService auditService;
    private final ToolJson toolJson;

    public record GuardResult(Orders order, String failureJson) {
        public boolean failed() {
            return failureJson != null;
        }
    }

    /**
     * 查订单并校验归属。
     *
     * @param identityAuditName 越权时写入 tool_audit 的工具名;传 null 则只记命中统计
     *                          (queryOrder 传 "queryOrder[identity]",其余工具传 null,与历史行为一致)
     */
    public GuardResult ownedOrder(Long conversationId, Long customerId, String orderNo, String identityAuditName) {
        Orders order = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, orderNo));
        if (order == null) {
            return new GuardResult(null, toolJson.result("NOT_FOUND", "订单不存在,请买家核对订单号"));
        }
        if (customerId == null || !order.getCustomerId().equals(customerId)) {
            if (identityAuditName != null) {
                auditService.write(conversationId, identityAuditName, "{}",
                        "越权访问他人订单 " + orderNo, "HIGH", "DENY", null, 0);
            }
            auditService.hit("CROSS_TENANT_ACCESS");
            return new GuardResult(null, toolJson.denied("CROSS_TENANT_ACCESS", "该订单不属于当前客户,拒绝操作"));
        }
        return new GuardResult(order, null);
    }
}
