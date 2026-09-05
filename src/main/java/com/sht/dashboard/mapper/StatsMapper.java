package com.sht.dashboard.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 看板取数:北极星指标(经审批执行的敏感操作数)与反指标(AI 答错引发的拦截) */
public interface StatsMapper {

    /** 今日接待数(新建会话) */
    @Select("SELECT COUNT(*) FROM conversation WHERE DATE(created_at) = CURDATE()")
    long todayConversations();

    /** 今日拦截次数(策略拒绝) */
    @Select("SELECT COUNT(*) FROM tool_audit WHERE policy_decision = 'DENY' AND DATE(created_at) = CURDATE()")
    long todayDenies();

    /** 今日经审批执行的退款金额(北极星) */
    @Select("SELECT IFNULL(SUM(amount), 0) FROM refund_request WHERE status = 'EXECUTED' AND DATE(created_at) = CURDATE()")
    double todayRefundAmount();

    /** 今日经审批执行的敏感操作数 */
    @Select("SELECT COUNT(*) FROM approval_task WHERE status = 'APPROVED' AND DATE(decided_at) = CURDATE()")
    long todayApprovedCount();

    /** 审批平均响应时长(秒) */
    @Select("SELECT IFNULL(AVG(TIMESTAMPDIFF(SECOND, created_at, decided_at)), 0) FROM approval_task "
            + "WHERE status IN ('APPROVED','REJECTED') AND DATE(decided_at) = CURDATE()")
    long avgApprovalSeconds();

    /** 今日策略命中分布(看板柱状图;剔除运维标记与逐客户频控计数) */
    @Select("SELECT rule_key AS ruleKey, hit_count AS hits FROM policy_hit_stat "
            + "WHERE date = CURDATE() AND hit_count > 0 "
            + "AND rule_key NOT LIKE 'policy_ingest%' AND rule_key NOT LIKE 'coupon_daily:%' "
            + "ORDER BY hit_count DESC LIMIT 8")
    List<Map<String, Object>> todayRuleHits();

    /** 近 7 天每日接待数(折线) */
    @Select("SELECT DATE_FORMAT(created_at, '%m-%d') AS day, COUNT(*) AS cnt FROM conversation "
            + "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
            + "GROUP BY DATE(created_at), DATE_FORMAT(created_at, '%m-%d') ORDER BY DATE(created_at)")
    List<Map<String, Object>> weekConversations();
}
