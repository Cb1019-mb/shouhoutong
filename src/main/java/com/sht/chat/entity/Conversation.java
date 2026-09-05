package com.sht.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    /** ACTIVE / CLOSED */
    private String status;

    private LocalDateTime createdAt;

    public static Conversation of(Long customerId) {
        Conversation c = new Conversation();
        c.setCustomerId(customerId);
        c.setStatus("ACTIVE");
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }
}
