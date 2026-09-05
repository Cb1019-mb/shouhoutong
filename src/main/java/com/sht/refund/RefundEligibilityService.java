package com.sht.refund;

import com.sht.order.entity.Orders;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 退款资格四态机(纯函数,便于全分支单测)。
 * 对应订单状态,拒绝/降级路径真实存在:
 *   PAID          → 仅退款,直接审批
 *   SHIPPED       → 需拦截快递后退款
 *   SIGNED ≤7天   → 退货退款,需客户寄回
 *   SIGNED >7天 / 生鲜/定制 → 拒绝,仅可发补偿券
 *   其他(CLOSED/REFUNDING/REFUNDED)→ 拒绝
 */
@Service
public class RefundEligibilityService {

    private static final int RETURN_WINDOW_DAYS = 7;
    private static final Set<String> NON_RETURNABLE = Set.of("FRESH", "CUSTOM");

    public RefundPlan judge(Orders order) {
        if (order.getCategory() != null && NON_RETURNABLE.contains(order.getCategory())) {
            return RefundPlan.rejectWithCoupon("生鲜/定制类商品不支持退货,可为您申请补偿券");
        }
        com.sht.order.OrderStatus status = com.sht.order.OrderStatus.parse(order.getStatus());
        if (status == null) {
            return RefundPlan.reject("订单当前状态(" + order.getStatus() + ")不支持退款");
        }
        return switch (status) {
            case PAID -> RefundPlan.directOnlyRefund();
            case SHIPPED -> RefundPlan.interceptRefund();
            case SIGNED -> isWithinReturnWindow(order.getSignedAt())
                    ? RefundPlan.returnRefund()
                    : RefundPlan.rejectWithCoupon("已签收超过 " + RETURN_WINDOW_DAYS + " 天,超出退货窗口,可为您申请补偿券");
            default -> RefundPlan.reject("订单当前状态(" + order.getStatus() + ")不支持退款");
        };
    }

    private boolean isWithinReturnWindow(LocalDateTime signedAt) {
        if (signedAt == null) {
            return true; // 缺签收时间时从宽,交给人工判断
        }
        return signedAt.isAfter(LocalDateTime.now().minusDays(RETURN_WINDOW_DAYS));
    }
}
