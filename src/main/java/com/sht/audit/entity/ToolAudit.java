package com.sht.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool_audit")
public class ToolAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private String toolName;

    private String paramsMasked;

    private String resultSummary;

    private String riskLevel;

    private String policyDecision;

    private Long approvalId;

    private Integer latencyMs;

    private LocalDateTime createdAt;
}
