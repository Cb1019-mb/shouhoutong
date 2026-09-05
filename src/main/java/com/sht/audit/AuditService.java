package com.sht.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.audit.entity.PolicyHitStat;
import com.sht.audit.entity.ToolAudit;
import com.sht.audit.mapper.PolicyHitStatMapper;
import com.sht.audit.mapper.ToolAuditMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审计与看板计数。所有工具调用的最终出口:ALLOW / APPROVE / DENY / EXPIRE 全落库。
 */
@Slf4j
@Service
public class AuditService {

    private final ToolAuditMapper toolAuditMapper;
    private final PolicyHitStatMapper statMapper;

    public AuditService(ToolAuditMapper toolAuditMapper, PolicyHitStatMapper statMapper) {
        this.toolAuditMapper = toolAuditMapper;
        this.statMapper = statMapper;
    }

    public void write(Long conversationId, String toolName, String paramsMasked,
                      String resultSummary, String riskLevel, String decision,
                      Long approvalId, long latencyMs) {
        ToolAudit audit = new ToolAudit();
        audit.setConversationId(conversationId);
        audit.setToolName(toolName);
        audit.setParamsMasked(paramsMasked);
        audit.setResultSummary(truncate(resultSummary, 240));
        audit.setRiskLevel(riskLevel);
        audit.setPolicyDecision(decision);
        audit.setApprovalId(approvalId);
        audit.setLatencyMs((int) latencyMs);
        audit.setCreatedAt(LocalDateTime.now());
        toolAuditMapper.insert(audit);
    }

    /** 限额/频控共用:日计数器,hit 后不超过 limit 返回 true */
    public boolean tryIncrementDaily(String ruleKey, int limit) {
        LocalDate today = LocalDate.now();
        PolicyHitStat stat = statMapper.selectOne(new LambdaQueryWrapper<PolicyHitStat>()
                .eq(PolicyHitStat::getRuleKey, ruleKey)
                .eq(PolicyHitStat::getDate, today));
        if (stat == null) {
            stat = new PolicyHitStat();
            stat.setRuleKey(ruleKey);
            stat.setHitCount(1);
            stat.setDate(today);
            try {
                statMapper.insert(stat);
                return limit >= 1;
            } catch (Exception e) {
                stat = statMapper.selectOne(new LambdaQueryWrapper<PolicyHitStat>()
                        .eq(PolicyHitStat::getRuleKey, ruleKey)
                        .eq(PolicyHitStat::getDate, today));
                if (stat == null) {
                    return false;
                }
            }
        }
        int next = stat.getHitCount() + 1;
        stat.setHitCount(next);
        statMapper.updateById(stat);
        return next <= limit;
    }

    public void hit(String ruleKey) {
        tryIncrementDaily(ruleKey, Integer.MAX_VALUE);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
