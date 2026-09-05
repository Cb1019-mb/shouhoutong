package com.sht.refund;

import com.sht.order.entity.Orders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 退款资格四态机全分支(含生鲜/定制与 7 天边界) */
class RefundEligibilityServiceTest {

    private final RefundEligibilityService service = new RefundEligibilityService();

    private Orders order(String status, String category, LocalDateTime signedAt) {
        Orders o = new Orders();
        o.setOrderNo("2025001");
        o.setStatus(status);
        o.setCategory(category);
        o.setSignedAt(signedAt);
        o.setAmount(BigDecimal.valueOf(100));
        return o;
    }

    @Test
    @DisplayName("已付款未发货 → 仅退款直接审批")
    void paid_directOnlyRefund() {
        assertThat(service.judge(order("PAID", "GENERAL", null)).verdict()).isEqualTo("APPROVABLE");
    }

    @Test
    @DisplayName("已发货 → 拦截后退款")
    void shipped_intercept() {
        RefundPlan p = service.judge(order("SHIPPED", "GENERAL", null));
        assertThat(p.verdict()).isEqualTo("APPROVABLE");
        assertThat(p.hint()).contains("拦截");
    }

    @Test
    @DisplayName("签收第 7 天边界内 → 退货退款")
    void signedDay7_withinWindow() {
        RefundPlan p = service.judge(order("SIGNED", "GENERAL", LocalDateTime.now().minusDays(7).plusMinutes(5)));
        assertThat(p.type()).isEqualTo("RETURN_REFUND");
    }

    @Test
    @DisplayName("签收超 7 天 → 拒绝但可发补偿券")
    void signedOver7_rejectWithCoupon() {
        RefundPlan p = service.judge(order("SIGNED", "GENERAL", LocalDateTime.now().minusDays(8)));
        assertThat(p.verdict()).isEqualTo("REJECT_WITH_COUPON");
    }

    @Test
    @DisplayName("生鲜品类 → 直接拒绝降级补偿券")
    void fresh_rejected() {
        assertThat(service.judge(order("SIGNED", "FRESH", LocalDateTime.now().minusDays(1))).verdict())
                .isEqualTo("REJECT_WITH_COUPON");
    }

    @Test
    @DisplayName("定制品类 → 直接拒绝降级补偿券")
    void custom_rejected() {
        assertThat(service.judge(order("PAID", "CUSTOM", null)).verdict()).isEqualTo("REJECT_WITH_COUPON");
    }

    @Test
    @DisplayName("已关闭订单 → 拒绝")
    void closed_rejected() {
        assertThat(service.judge(order("CLOSED", "GENERAL", null)).verdict()).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("已退款订单 → 拒绝")
    void refunded_rejected() {
        assertThat(service.judge(order("REFUNDED", "GENERAL", null)).verdict()).isEqualTo("REJECT");
    }
}
