package com.sht.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.audit.entity.ToolAudit;
import com.sht.audit.mapper.ToolAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** 审计中心:按会话查看全部工具调用与策略裁决记录 */
@Controller
@RequiredArgsConstructor
public class AuditPageController {

    private final ToolAuditMapper toolAuditMapper;

    @GetMapping("/audit")
    public String audit(@RequestParam(value = "conversationId", required = false) Long conversationId,
                        Model model) {
        LambdaQueryWrapper<ToolAudit> qw = new LambdaQueryWrapper<ToolAudit>()
                .orderByDesc(ToolAudit::getId)
                .last("limit 100");
        if (conversationId != null) {
            qw.eq(ToolAudit::getConversationId, conversationId);
        }
        List<ToolAudit> audits = toolAuditMapper.selectList(qw);
        model.addAttribute("audits", audits);
        model.addAttribute("conversationId", conversationId);
        return "audit";
    }
}
