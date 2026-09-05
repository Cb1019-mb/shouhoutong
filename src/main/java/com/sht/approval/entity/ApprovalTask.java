package com.sht.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_task")
public class ApprovalTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long toolAuditId;

    private Long conversationId;

    /** 冗余订单号:提交防重与列表展示 */
    private String orderNo;

    /** 操作预览(改前/改后 diff),审批卡片直接展示 */
    private String previewJson;

    /** PENDING / APPROVED / REJECTED / EXPIRED */
    private String status;

    private String feishuMsgId;

    private String decider;

    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;
}
