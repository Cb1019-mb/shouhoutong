package com.sht.approval.feishu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 默认实现:未配置飞书应用时静默跳过,网页审批中心兜底 */
@Component
@ConditionalOnProperty(name = "feishu.enabled", havingValue = "false", matchIfMissing = true)
public class NoopFeishuCardService implements FeishuCardService {

    @Override
    public String sendRefundCard(Long taskId, java.util.Map<String, Object> preview) {
        return null;
    }

    @Override
    public void updateCard(String feishuMsgId, String statusText, String decider) {
        // noop
    }
}
