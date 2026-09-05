package com.sht.refund;

import java.util.Map;

/**
 * 四态机裁决结果,直接序列化进工具返回与(W3 的)审批预览。
 */
public record RefundPlan(String verdict, String type, String hint, String rejectReason) {

    /** ✅ 仅退款,直接审批 */
    public static RefundPlan directOnlyRefund() {
        return new RefundPlan("APPROVABLE", "ONLY_REFUND", "未发货订单,可直接退款", null);
    }

    /** ⚠️ 拦截快递后退款 */
    public static RefundPlan interceptRefund() {
        return new RefundPlan("APPROVABLE", "ONLY_REFUND", "已发货,审批通过后需先拦截快递再退款", null);
    }

    /** ⚠️ 退货退款,客户寄回 */
    public static RefundPlan returnRefund() {
        return new RefundPlan("APPROVABLE", "RETURN_REFUND",
                "签收7天内,支持退货退款,需客户寄回商品(退货地址:广东省深圳市南山区售后通仓,收件人:售后组)", null);
    }

    /** ❌ 拒绝,可发补偿券 */
    public static RefundPlan rejectWithCoupon(String reason) {
        return new RefundPlan("REJECT_WITH_COUPON", null, null, reason);
    }

    /** ❌ 拒绝 */
    public static RefundPlan reject(String reason) {
        return new RefundPlan("REJECT", null, null, reason);
    }

    public Map<String, Object> toMap() {
        return new java.util.LinkedHashMap<>(Map.of(
                "verdict", verdict,
                "type", type == null ? "" : type,
                "hint", hint == null ? "" : hint,
                "rejectReason", rejectReason == null ? "" : rejectReason));
    }
}
