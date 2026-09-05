package com.sht.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.mapper.ApprovalTaskMapper;
import com.sht.audit.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审批过期扫描:超时未处理的 PENDING 任务自动过期拒绝(业务防线之一)。
 * 过期同样走 CAS,避免与人工决定并发冲突。
 */
@Slf4j
@Component
public class ExpireSweeper {

    private final ApprovalTaskMapper taskMapper;
    private final ApprovalFlowService approvalFlowService;
    private final AuditService auditService;
    private final com.sht.settings.SettingsService settingsService;
    private final long expireMinutesFallback;

    public ExpireSweeper(ApprovalTaskMapper taskMapper, ApprovalFlowService approvalFlowService,
                         AuditService auditService,
                         com.sht.settings.SettingsService settingsService,
                         @Value("${approval.expire-minutes:1440}") long expireMinutesFallback) {
        this.taskMapper = taskMapper;
        this.approvalFlowService = approvalFlowService;
        this.auditService = auditService;
        this.settingsService = settingsService;
        this.expireMinutesFallback = expireMinutesFallback;
    }

    @Scheduled(fixedDelayString = "${approval.sweep-interval-ms:60000}")
    public void sweep() {
        // 审批过期时间运行时可调(设置页),yml 值兜底
        long expireMinutes;
        try {
            expireMinutes = settingsService.getInt(com.sht.settings.SettingKey.RULE_EXPIRE_MINUTES);
        } catch (Exception e) {
            expireMinutes = expireMinutesFallback;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(expireMinutes);
        List<ApprovalTask> expired = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getStatus, "PENDING")
                .lt(ApprovalTask::getCreatedAt, deadline));
        for (ApprovalTask task : expired) {
            int updated = taskMapper.update(null, new LambdaUpdateWrapper<ApprovalTask>()
                    .eq(ApprovalTask::getId, task.getId())
                    .eq(ApprovalTask::getStatus, "PENDING")
                    .set(ApprovalTask::getStatus, "EXPIRED")
                    .set(ApprovalTask::getDecidedAt, LocalDateTime.now())
                    .set(ApprovalTask::getDecider, "system"));
            if (updated > 0) {
                auditService.write(task.getConversationId(), "approvalExpire", "{}",
                        "审批超时自动过期 taskId=" + task.getId(), "HIGH", "EXPIRE", task.getId(), 0);
                auditService.hit("APPROVAL_EXPIRED");
                approvalFlowService.notify(task.getConversationId(), "您的退款审批超时未处理,已自动关闭。"
                        + "如仍需退款,请联系人工客服为您重新提交。");
                log.info("审批任务过期 taskId={}", task.getId());
            }
        }
    }
}
