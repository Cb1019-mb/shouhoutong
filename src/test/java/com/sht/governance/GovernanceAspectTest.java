package com.sht.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.approval.ApprovalFlowService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 治理切面三分支:DENY 不执行 / APPROVAL 转审批不执行 / ALLOW 透传并写审计 */
class GovernanceAspectTest {

    /** 仅为提供 @Governed 注解的 Method 反射源 */
    static class Dummy {
        @Governed("queryOrder")
        public String query(String orderNo) { return "ok"; }

        @Governed("createRefund")
        public String refund(String orderNo) { return "ok"; }
    }

    private PolicyEngine policyEngine;
    private com.sht.audit.AuditService auditService;
    private ApprovalFlowService approvalFlowService;
    private GovernanceAspect aspect;

    @BeforeEach
    void setUp() {
        policyEngine = mock(PolicyEngine.class);
        auditService = mock(com.sht.audit.AuditService.class);
        approvalFlowService = mock(ApprovalFlowService.class);
        aspect = new GovernanceAspect(policyEngine, auditService,
                new ObjectMapper(), new ToolJson(new ObjectMapper()), approvalFlowService);
    }

    private ProceedingJoinPoint joinPoint(String methodName, Object[] args) throws Exception {
        Method method = Dummy.class.getDeclaredMethod(methodName, String.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getParameterNames()).thenReturn(new String[]{"orderNo"});
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    @Test
    @DisplayName("DENY:返回拒绝 JSON,业务方法不执行,审计落 DENY")
    void deny_shortCircuits() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("query", new Object[]{"2025001"});
        when(policyEngine.decide(isNull(), eq("queryOrder"), any()))
                .thenReturn(PolicyDecision.deny("DEFAULT_DENY", "未注册的工具调用"));

        Object out = aspect.govern(pjp, Dummy.class.getDeclaredMethod("query", String.class)
                .getAnnotation(Governed.class));

        assertThat(String.valueOf(out)).contains("DENIED").contains("DEFAULT_DENY");
        verify(pjp, never()).proceed();
        verify(auditService).write(isNull(), eq("queryOrder"), anyString(), anyString(),
                eq("HIGH"), eq("DENY"), isNull(), anyLong());
        verify(auditService).hit("DEFAULT_DENY");
    }

    @Test
    @DisplayName("APPROVAL:转审批流,业务方法不执行")
    void approval_goesToFlow() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("refund", new Object[]{"2025001"});
        when(policyEngine.decide(isNull(), eq("createRefund"), any()))
                .thenReturn(new PolicyDecision(PolicyAction.APPROVAL, RiskLevel.HIGH,
                        "HIGH_RISK_APPROVAL", "高风险操作,需店主审批"));
        when(approvalFlowService.submit(eq("createRefund"), isNull(), any(), anyLong()))
                .thenReturn("{\"status\":\"SUBMITTED_FOR_APPROVAL\"}");

        Object out = aspect.govern(pjp, Dummy.class.getDeclaredMethod("refund", String.class)
                .getAnnotation(Governed.class));

        assertThat(String.valueOf(out)).contains("SUBMITTED_FOR_APPROVAL");
        verify(pjp, never()).proceed();
        verify(approvalFlowService).submit(eq("createRefund"), isNull(), any(), anyLong());
    }

    @Test
    @DisplayName("ALLOW:透传执行,审计落 ALLOW 并计数")
    void allow_proceedsAndAudits() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint("query", new Object[]{"2025001"});
        when(policyEngine.decide(isNull(), eq("queryOrder"), any()))
                .thenReturn(PolicyDecision.allow(RiskLevel.LOW));
        when(pjp.proceed()).thenReturn("{\"status\":\"OK\"}");

        Object out = aspect.govern(pjp, Dummy.class.getDeclaredMethod("query", String.class)
                .getAnnotation(Governed.class));

        assertThat(out).isEqualTo("{\"status\":\"OK\"}");
        verify(pjp).proceed();
        verify(auditService).write(isNull(), eq("queryOrder"), anyString(), anyString(),
                eq("LOW"), eq("ALLOW"), isNull(), anyLong());
        verify(auditService).hit("ALLOWED");
    }
}
