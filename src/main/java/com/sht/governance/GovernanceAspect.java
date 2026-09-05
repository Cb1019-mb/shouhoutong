package com.sht.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.audit.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具治理切面:环绕所有 @Governed 方法。
 * 流程:组装参数 → 策略引擎裁决 →
 *   DENY     → 落审计,返回结构化拒绝 JSON(不执行业务方法)
 *   ALLOW    → 执行业务方法,落 ALLOW 审计
 *   APPROVAL → W2 先返回"需店主审批"话术,W3 在此处挂起审批闭环
 */
@Aspect
@Component
public class GovernanceAspect {

    private final PolicyEngine policyEngine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ToolJson toolJson;
    private final com.sht.approval.ApprovalFlowService approvalFlowService;

    public GovernanceAspect(PolicyEngine policyEngine, AuditService auditService,
                            ObjectMapper objectMapper, ToolJson toolJson,
                            com.sht.approval.ApprovalFlowService approvalFlowService) {
        this.policyEngine = policyEngine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.toolJson = toolJson;
        this.approvalFlowService = approvalFlowService;
    }

    @Around("@annotation(governed)")
    public Object govern(ProceedingJoinPoint pjp, Governed governed) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = governed.value();
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();

        Map<String, Object> params = new LinkedHashMap<>();
        Long conversationId = null;
        String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof ToolContext ctx) {
                Object cid = ctx.getContext().get("conversationId");
                conversationId = cid instanceof Number n ? n.longValue() : null;
                continue;
            }
            params.put(names != null && i < names.length ? names[i] : ("arg" + i), args[i]);
        }

        String paramsJson;
        try {
            paramsJson = objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            paramsJson = String.valueOf(params);
        }
        String masked = MaskingUtil.mask(paramsJson);

        PolicyDecision decision = policyEngine.decide(conversationId, toolName, params);

        // APPROVAL 分支:进入审批闭环(dry-run → 审批任务 → 飞书/网页审批),
        // 不穿透到工具体;审计与任务落库由 ApprovalFlowService 负责(含 approval_id 关联)。
        if (decision.action() == PolicyAction.APPROVAL) {
            return approvalFlowService.submit(toolName, conversationId, params, start);
        }

        if (decision.denied()) {
            long latency = System.currentTimeMillis() - start;
            auditService.write(conversationId, toolName, masked,
                    decision.reason(), decision.risk().name(), "DENY", null, latency);
            auditService.hit(decision.ruleKey());
            return toolJson.denied(decision.ruleKey(), decision.reason());
        }

        Object result = pjp.proceed();

        long latency = System.currentTimeMillis() - start;
        String decisionLabel = decision.action() == PolicyAction.APPROVAL ? "APPROVE" : "ALLOW";
        auditService.write(conversationId, toolName, masked,
                summarize(result), decision.risk().name(), decisionLabel, null, latency);
        auditService.hit(decision.ruleKey());
        return result;
    }

    private String summarize(Object result) {
        String s = String.valueOf(result);
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
