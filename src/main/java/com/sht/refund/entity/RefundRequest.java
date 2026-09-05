package com.sht.refund.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("refund_request")
public class RefundRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String reason;

    private BigDecimal amount;

    /** ONLY_REFUND / RETURN_REFUND */
    private String type;

    /** PENDING_APPROVAL / EXECUTED / REJECTED */
    private String status;

    private Long approvalId;

    private LocalDateTime createdAt;
}
