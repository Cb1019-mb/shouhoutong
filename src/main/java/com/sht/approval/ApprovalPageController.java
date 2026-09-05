package com.sht.approval;

import com.sht.approval.entity.ApprovalTask;
import com.sht.approval.mapper.ApprovalTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 审批中心页面 */
@Controller
@RequiredArgsConstructor
public class ApprovalPageController {

    private final ApprovalTaskMapper taskMapper;
    private final ApprovalFlowService approvalFlowService;

    @GetMapping("/approvals")
    public String approvals(Model model) {
        var pending = taskMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getStatus, "PENDING")
                .orderByDesc(ApprovalTask::getId));
        var decided = taskMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalTask>()
                .in(ApprovalTask::getStatus, "APPROVED", "REJECTED", "EXPIRED", "EXECUTE_FAILED")
                .orderByDesc(ApprovalTask::getDecidedAt)
                .last("limit 20"));
        model.addAttribute("pending", pending.stream()
                .map(t -> new TaskView(t, approvalFlowService.previewOf(t)))
                .toList());
        model.addAttribute("decided", decided.stream()
                .map(t -> new TaskView(t, approvalFlowService.previewOf(t)))
                .toList());
        return "approvals";
    }

    public record TaskView(ApprovalTask task, java.util.Map<String, Object> preview) {}
}
