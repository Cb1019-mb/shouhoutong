package com.sht.approval.feishu;

import java.util.Map;

/**
 * 飞书卡片适配。未配置飞书自建应用时(feishu.enabled=false)使用 Noop 实现,
 * 网页审批中心为保底审批入口;配置后走 HTTP 卡片推送。
 */
public interface FeishuCardService {

    /** 发送退款审批卡片,返回 feishu_msg_id(未启用返回 null) */
    String sendRefundCard(Long taskId, Map<String, Object> preview);

    /** 审批决定后更新卡片终态 */
    void updateCard(String feishuMsgId, String statusText, String decider);
}
