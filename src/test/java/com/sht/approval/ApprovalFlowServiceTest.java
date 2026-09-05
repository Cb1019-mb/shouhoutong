package com.sht.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.mapper.ApprovalTaskMapper;
import com.sht.audit.AuditService;
import com.sht.chat.SseService;
import com.sht.chat.entity.Message;
import com.sht.chat.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** 审批决定状态机:CAS 冲突 / 批准执行 / 执行失败回写 / 拒绝通知 */
class ApprovalFlowServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 纯单测无 Spring 上下文,LambdaUpdateWrapper 需要实体的 TableInfo 缓存,手动初始化一次
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ApprovalTask.class);
    }

    private ApprovalTaskMapper taskMapper;
    private ApprovalRecordService recordService;
    private ApprovalExecutionService executionService;
    private com.sht.approval.feishu.FeishuCardService feishuCardService;
    private MessageMapper messageMapper;
    private SseService sseService;
    private AuditService auditService;
    private ApprovalFlowService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(ApprovalTaskMapper.class);
        recordService = mock(ApprovalRecordService.class);
        executionService = mock(ApprovalExecutionService.class);
        feishuCardService = mock(com.sht.approval.feishu.FeishuCardService.class);
        messageMapper = mock(MessageMapper.class);
        sseService = mock(SseService.class);
        auditService = mock(AuditService.class);
        DryRunService dryRunService = mock(DryRunService.class);
        service = new ApprovalFlowService(dryRunService, taskMapper, recordService, auditService,
                executionService, feishuCardService, messageMapper, sseService, new ObjectMapper());
    }

    private ApprovalTask task(long id, String status) {
        ApprovalTask t = new ApprovalTask();
        t.setId(id);
        t.setConversationId(30L);
        t.setOrderNo("2025001");
        t.setStatus(status);
        t.setCreatedAt(LocalDateTime.now());
        t.setPreviewJson("{\"orderNo\":\"2025001\",\"amount\":100,\"currentStatus\":\"PAID\"}");
        return t;
    }

    @Test
    @DisplayName("任务不存在 → 错误提示")
    void missingTask() {
        when(taskMapper.selectById(9L)).thenReturn(null);
        assertThat(service.decide(9L, ApprovalFlowService.Decision.APPROVED, "测试"))
                .isEqualTo("审批任务不存在");
    }

    @Test
    @DisplayName("CAS 抢状态失败(已被处理)→ 返回冲突提示,不再执行")
    void casConflict() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "PENDING"));
        when(taskMapper.update(any(), any())).thenReturn(0);
        String err = service.decide(1L, ApprovalFlowService.Decision.APPROVED, "测试");
        assertThat(err).isEqualTo("该审批已处理过,请刷新");
        verify(executionService, never()).executeApproved(any());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("批准成功:单事务执行 + 客户收到通过通知")
    void approveExecutesAndNotifies() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "PENDING"));
        when(taskMapper.update(any(), any())).thenReturn(1);

        String err = service.decide(1L, ApprovalFlowService.Decision.APPROVED, "测试");

        assertThat(err).isNull();
        verify(executionService).executeApproved(any(ApprovalTask.class));
        verify(messageMapper).insert(argThat((Message m) -> m.getContent().contains("已通过店主审批")));
        verify(sseService).pushMessage(eq(30L), anyString());
        verify(feishuCardService).updateCard(any(), contains("已批准"), anyString());
    }

    @Test
    @DisplayName("批准后执行异常:任务回写 EXECUTE_FAILED,客户收到转人工通知")
    void approveExecutionFailureWritesBack() {
        when(taskMapper.selectById(1L)).thenReturn(task(1, "PENDING"));
        when(taskMapper.update(any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("订单状态已变化"))
                .when(executionService).executeApproved(any());

        String err = service.decide(1L, ApprovalFlowService.Decision.APPROVED, "测试");

        assertThat(err).isNull();
        verify(taskMapper, times(2)).update(any(), any()); // CAS + 回写
        verify(auditService).hit("EXECUTE_FAILED");
        verify(messageMapper).insert(argThat((Message m) -> m.getContent().contains("人工处理")));
    }

    @Test
    @DisplayName("拒绝:订单不动,客户收到拒绝通知")
    void rejectNotifies() {
        when(taskMapper.selectById(2L)).thenReturn(task(2, "PENDING"));
        when(taskMapper.update(any(), any())).thenReturn(1);

        String err = service.decide(2L, ApprovalFlowService.Decision.REJECTED, "测试");

        assertThat(err).isNull();
        verify(executionService, never()).executeApproved(any());
        verify(messageMapper).insert(argThat((Message m) -> m.getContent().contains("未通过审批")));
        verify(feishuCardService).updateCard(any(), contains("已拒绝"), anyString());
    }
}
