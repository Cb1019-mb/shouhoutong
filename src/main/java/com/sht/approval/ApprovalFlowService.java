package com.sht.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.feishu.FeishuCardService;
import com.sht.approval.mapper.ApprovalTaskMapper;
import com.sht.audit.AuditService;
import com.sht.chat.SseService;
import com.sht.chat.entity.Message;
import com.sht.chat.mapper.MessageMapper;
import com.sht.governance.MaskingUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批流:
 * submit —— 高风险工具调用 → dry-run 预览 → 落 tool_audit(APPROVE) + approval_task(PENDING)
 *           → 飞书卡片(配置门控)→ 立即返回话术(请求线程不等待,避坑②)
 * decide —— 网页审批中心/飞书回调共用入口,CAS 抢状态(两端同时点只生效一次)
 *           → 批准则事务执行真实写库 → 结果消息落库并 SSE 推送客户
 */
@Slf4j
@Service
public class ApprovalFlowService {

    public enum Decision { APPROVED, REJECTED }

    private final DryRunService dryRunService;
    private final ApprovalTaskMapper taskMapper;
    private final ApprovalRecordService recordService;
    private final AuditService auditService;
    private final ApprovalExecutionService executionService;
    private final FeishuCardService feishuCardService;
    private final MessageMapper messageMapper;
    private final SseService sseService;
    private final ObjectMapper objectMapper;

    public ApprovalFlowService(DryRunService dryRunService, ApprovalTaskMapper taskMapper,
                               ApprovalRecordService recordService, AuditService auditService,
                               ApprovalExecutionService executionService,
                               FeishuCardService feishuCardService,
                               MessageMapper messageMapper, SseService sseService,
                               ObjectMapper objectMapper) {
        this.dryRunService = dryRunService;
        this.taskMapper = taskMapper;
        this.recordService = recordService;
        this.auditService = auditService;
        this.executionService = executionService;
        this.feishuCardService = feishuCardService;
        this.messageMapper = messageMapper;
        this.sseService = sseService;
        this.objectMapper = objectMapper;
    }

    /** 高风险工具调用入口(治理切面 APPROVAL 分支),返回给 LLM 的结构化结果 */
    public String submit(String toolName, Long conversationId, Map<String, Object> params, long startMs) {
        DryRunService.DryRunResult dry = dryRunService.dryRun(toolName, conversationId, params);
        if (!dry.ok()) {
            auditService.write(conversationId, toolName, MaskingUtil.mask(toJson(params)),
                    "预览阶段拦截", "HIGH", "DENY", null, elapsed(startMs));
            return dry.toolResultJson();
        }
        Map<String, Object> preview = dry.preview();

        // 1. 落审计(APPROVE)+ 审批任务(PENDING)——同事务,防孤儿审计行
        ApprovalTask task = recordService.record(toolName, conversationId,
                MaskingUtil.mask(toJson(params)),
                "提交审批:" + preview.get("orderNo") + " ¥" + preview.get("amount"),
                (int) elapsed(startMs),
                String.valueOf(preview.get("orderNo")),
                toJson(preview));

        // 2. 飞书卡片(未配置飞书应用时跳过,网页审批中心为保底路径)
        try {
            String msgId = feishuCardService.sendRefundCard(task.getId(), preview);
            if (msgId != null) {
                task.setFeishuMsgId(msgId);
                taskMapper.updateById(task);
            }
        } catch (Exception e) {
            log.warn("飞书卡片发送失败,走网页审批中心降级:{}", e.getMessage());
        }

        // 3. 立即返回话术,请求结束——审批异步挂起
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "SUBMITTED_FOR_APPROVAL");
        out.put("approvalId", task.getId());
        out.put("refundType", preview.get("refundType"));
        out.put("amount", preview.get("amount"));
        out.put("planHint", preview.get("planHint"));
        out.put("message", "退款申请已提交店主审批,审批结果将在此会话通知您");
        return toJson(out);
    }

    /** 审批决定:网页审批中心与飞书回调共用。返回 null=成功,否则为错误信息 */
    public String decide(Long taskId, Decision decision, String decider) {
        ApprovalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return "审批任务不存在";
        }
        // CAS 抢状态:并发双点只生效一次
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<ApprovalTask>()
                .eq(ApprovalTask::getId, taskId)
                .eq(ApprovalTask::getStatus, "PENDING")
                .set(ApprovalTask::getStatus, decision.name())
                .set(ApprovalTask::getDecider, decider)
                .set(ApprovalTask::getDecidedAt, LocalDateTime.now()));
        if (updated == 0) {
            return "该审批已处理过,请刷新";
        }
        task.setStatus(decision.name());
        task.setDecider(decider);
        auditService.hit("decision_" + decision.name().toLowerCase());

        String orderNo = str(previewOf(task), "orderNo");
        if (decision == Decision.APPROVED) {
            try {
                executionService.executeApproved(task);
            } catch (Exception e) {
                log.error("审批执行失败 taskId={}", taskId, e);
                // 状态回写:不再留在 APPROVED,避免"显示已批准却无退款单"的数据不一致
                taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getId, taskId)
                        .set(ApprovalTask::getStatus, "EXECUTE_FAILED"));
                task.setStatus("EXECUTE_FAILED");
                auditService.hit("EXECUTE_FAILED");
                notify(task.getConversationId(), "您的订单 " + orderNo
                        + " 退款审批已通过,但系统执行时出现异常,客服将尽快人工处理。");
                feishuCardService.updateCard(task.getFeishuMsgId(), "已批准(执行异常,转人工)", decider);
                return null;
            }
            notify(task.getConversationId(), "好消息!您的订单 " + orderNo + " 退款申请已通过店主审批,"
                    + "退款金额 " + str(previewOf(task), "amount") + " 元将原路退回,订单状态已更新为【退款中】。"
                    + (com.sht.order.OrderStatus.SHIPPED.name().equals(str(previewOf(task), "currentStatus"))
                    ? "我们会先拦截快递,请保持手机畅通。" : ""));
            feishuCardService.updateCard(task.getFeishuMsgId(), "已批准", decider);
        } else {
            notify(task.getConversationId(), "很抱歉,您的订单 " + orderNo
                    + " 退款申请未通过审批。如有疑问请联系人工客服,我们会为您进一步核实。");
            feishuCardService.updateCard(task.getFeishuMsgId(), "已拒绝", decider);
        }
        return null;
    }

    /** 审批结果通知:落库(回放可见)+ SSE 实时推给在线客户 */
    public void notify(Long conversationId, String text) {
        messageMapper.insert(Message.of(conversationId, "assistant", text));
        sseService.pushMessage(conversationId, text);
    }

    public Map<String, Object> previewOf(ApprovalTask task) {
        try {
            return objectMapper.readValue(task.getPreviewJson(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }
}
