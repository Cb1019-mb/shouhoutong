package com.sht.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.audit.AuditService;
import com.sht.governance.IdentityContext;
import com.sht.governance.PolicyEngine;
import com.sht.governance.ToolGuards;
import com.sht.governance.ToolJson;
import com.sht.order.entity.Orders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** queryMyOrders:身份绑定过滤(只回本人订单)+ 无身份拒绝 */
class AfterSaleToolsTest {

    private IdentityContext identityContext;
    private ToolGuards toolGuards;
    private AfterSaleTools tools;

    @BeforeEach
    void setUp() {
        identityContext = mock(IdentityContext.class);
        toolGuards = mock(ToolGuards.class);
        tools = new AfterSaleTools(identityContext, mock(PolicyEngine.class),
                mock(AuditService.class), new ToolJson(new ObjectMapper()), toolGuards);
    }

    private ToolContext contextOf(long conversationId) {
        return new ToolContext(Map.of("conversationId", conversationId));
    }

    private Orders order(long id, long customerId, String orderNo, String product, String status) {
        Orders o = new Orders();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setOrderNo(orderNo);
        o.setProductName(product);
        o.setAmount(BigDecimal.valueOf(399, 0));
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("已绑定身份:返回该客户全部订单,不混入他人订单")
    void returnsOwnedOrdersOnly() {
        when(identityContext.resolve(7L)).thenReturn(1L);
        when(toolGuards.ownedOrders(1L)).thenReturn(List.of(
                order(2, 1, "2025002", "运动鞋", "SIGNED"),
                order(5, 1, "2025005", "牛仔裤", "SIGNED")));

        String out = tools.queryMyOrders("list", contextOf(7L));

        assertThat(out).contains("\"status\":\"OK\"")
                .contains("\"count\":2")
                .contains("2025002").contains("2025005")
                .contains("运动鞋").contains("SIGNED");
        // mock 只供给 customerId=1 的订单,结果里不该出现其他订单号
        assertThat(out).doesNotContain("2025006");
    }

    @Test
    @DisplayName("无订单客户:返回 OK 空列表,让 LLM 如实转述")
    void returnsEmptyListWhenNoOrders() {
        when(identityContext.resolve(8L)).thenReturn(9L);
        when(toolGuards.ownedOrders(9L)).thenReturn(List.of());

        String out = tools.queryMyOrders("list", contextOf(8L));

        assertThat(out).contains("\"status\":\"OK\"").contains("\"count\":0").contains("\"orders\":[]");
    }

    @Test
    @DisplayName("身份缺失:返回 IDENTITY_MISSING,不触发查询")
    void deniesWhenIdentityMissing() {
        when(identityContext.resolve(9L)).thenReturn(null);

        String out = tools.queryMyOrders("list", contextOf(9L));

        assertThat(out).contains("IDENTITY_MISSING");
        org.mockito.Mockito.verifyNoInteractions(toolGuards);
    }
}
