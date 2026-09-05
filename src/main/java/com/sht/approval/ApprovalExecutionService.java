package com.sht.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.mapper.ApprovalTaskMapper;
import com.sht.order.entity.Orders;
import com.sht.order.mapper.OrdersMapper;
import com.sht.refund.entity.RefundRequest;
import com.sht.refund.mapper.RefundRequestMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审批通过后的真实执行(红线 B 的另一半:此刻才允许写业务库)。
 * 单事务:订单状态机流转 + 退款单落库,失败整体回滚。
 */
@Slf4j
@Service
public class ApprovalExecutionService {

    private final ApprovalTaskMapper taskMapper;
    private final OrdersMapper ordersMapper;
    private final RefundRequestMapper refundRequestMapper;
    private final ObjectMapper objectMapper;

    public ApprovalExecutionService(ApprovalTaskMapper taskMapper, OrdersMapper ordersMapper,
                                    RefundRequestMapper refundRequestMapper, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.ordersMapper = ordersMapper;
        this.refundRequestMapper = refundRequestMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void executeApproved(ApprovalTask task) {
        Map<String, Object> preview;
        try {
            preview = objectMapper.readValue(task.getPreviewJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("preview_json 解析失败", e);
        }
        String orderNo = String.valueOf(preview.get("orderNo"));
        Orders order = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, orderNo));
        if (order == null) {
            throw new IllegalStateException("订单不存在: " + orderNo);
        }
        // 订单状态机流转(PAID/SHIPPED/SIGNED → REFUNDING)
        int updated = ordersMapper.update(null, new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, order.getId())
                .in(Orders::getStatus,
                        com.sht.order.OrderStatus.PAID.name(),
                        com.sht.order.OrderStatus.SHIPPED.name(),
                        com.sht.order.OrderStatus.SIGNED.name())
                .set(Orders::getStatus, com.sht.order.OrderStatus.REFUNDING.name()));
        if (updated == 0) {
            throw new IllegalStateException("订单状态已变化,拒绝执行: " + orderNo);
        }
        // 退款单落库
        RefundRequest refund = new RefundRequest();
        refund.setOrderId(order.getId());
        refund.setReason(String.valueOf(preview.getOrDefault("reason", "")));
        refund.setAmount(new java.math.BigDecimal(String.valueOf(preview.get("amount"))));
        refund.setType(String.valueOf(preview.getOrDefault("refundType", "ONLY_REFUND")));
        refund.setStatus("EXECUTED");
        refund.setApprovalId(task.getId());
        refund.setCreatedAt(LocalDateTime.now());
        refundRequestMapper.insert(refund);
        log.info("审批执行完成 taskId={} orderNo={} → REFUNDING", task.getId(), orderNo);
    }
}
