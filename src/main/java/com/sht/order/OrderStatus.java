package com.sht.order;

import java.util.Set;

/**
 * 订单状态机。数据库仍存 VARCHAR(不动表),代码侧以枚举常量引用,
 * 状态字面量的拼写错误从此在编译期暴露。
 */
public enum OrderStatus {
    PAID, SHIPPED, SIGNED, CLOSED, REFUNDING, REFUNDED;

    /** 允许发起退款/执行退款流转的前置状态 */
    public static final Set<OrderStatus> REFUNDABLE = Set.of(PAID, SHIPPED, SIGNED);

    /** 非法/未知值返回 null,由调用方决定兜底行为 */
    public static OrderStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}
