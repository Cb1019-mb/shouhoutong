package com.sht.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.mapper.ApprovalTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 审批中心:网页审批入口(飞书故障时的降级路径)+ 审批决定 API */
@RestController
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalTaskMapper taskMapper;
    private final ApprovalFlowService approvalFlowService;

    public record DecideRequest(String decision, String decider) {}

    @PostMapping("/api/approvals/{id}/decide")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable Long id,
                                                      @RequestBody DecideRequest req) {
        ApprovalFlowService.Decision decision;
        try {
            decision = ApprovalFlowService.Decision.valueOf(req.decision());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision 必须为 APPROVED 或 REJECTED"));
        }
        String decider = (req.decider() == null || req.decider().isBlank()) ? "店主" : req.decider();
        String error = approvalFlowService.decide(id, decision, decider);
        if (error != null) {
            return ResponseEntity.status(409).body(Map.of("error", error));
        }
        return ResponseEntity.ok(Map.of("status", "OK", "decision", decision.name()));
    }

    @GetMapping("/api/approvals")
    public Map<String, Object> list() {
        List<ApprovalTask> pending = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getStatus, "PENDING")
                .orderByDesc(ApprovalTask::getId));
        List<ApprovalTask> decided = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .in(ApprovalTask::getStatus, "APPROVED", "REJECTED", "EXPIRED", "EXECUTE_FAILED")
                .orderByDesc(ApprovalTask::getDecidedAt)
                .last("limit 20"));
        return Map.of("pending", pending, "decided", decided);
    }
}
