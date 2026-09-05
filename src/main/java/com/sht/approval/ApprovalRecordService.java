package com.sht.approval;

import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.mapper.ApprovalTaskMapper;
import com.sht.audit.AuditService;
import com.sht.audit.entity.ToolAudit;
import com.sht.audit.mapper.ToolAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审批提交的落库步骤:审计行与审批任务同事务写入。
 * 修复点:原 submit() 两步 insert 无事务,中间失败会留下无 approval_id 关联的孤儿审计行
 * ——审计完整性是本项目的核心卖点,不允许半截记录。
 */
@Service
@RequiredArgsConstructor
public class ApprovalRecordService {

    private final ToolAuditMapper auditMapper;
    private final ApprovalTaskMapper taskMapper;
    private final AuditService auditService;

    @Transactional
    public ApprovalTask record(String toolName, Long conversationId, String paramsMasked,
                               String summary, int latencyMs, String orderNo, String previewJson) {
        ToolAudit audit = new ToolAudit();
        audit.setConversationId(conversationId);
        audit.setToolName(toolName);
        audit.setParamsMasked(paramsMasked);
        audit.setResultSummary(summary);
        audit.setRiskLevel("HIGH");
        audit.setPolicyDecision("APPROVE");
        audit.setLatencyMs(latencyMs);
        audit.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(audit);
        auditService.hit("HIGH_RISK_APPROVAL");

        ApprovalTask task = new ApprovalTask();
        task.setToolAuditId(audit.getId());
        task.setConversationId(conversationId);
        task.setOrderNo(orderNo);
        task.setPreviewJson(previewJson);
        task.setStatus("PENDING");
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        audit.setApprovalId(task.getId());
        auditMapper.updateById(audit);
        return task;
    }
}
