package com.sht.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Orders {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    private String orderNo;

    private String productName;

    /** GENERAL / FRESH / CUSTOM */
    private String category;

    private BigDecimal amount;

    /** PAID / SHIPPED / SIGNED / CLOSED / REFUNDING / REFUNDED */
    private String status;

    private LocalDateTime signedAt;

    private LocalDateTime createdAt;
}
