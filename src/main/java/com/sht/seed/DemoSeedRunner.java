package com.sht.seed;

import com.sht.audit.mapper.PolicyHitStatMapper;
import com.sht.chat.entity.Customer;
import com.sht.chat.mapper.CustomerMapper;
import com.sht.order.entity.Orders;
import com.sht.order.mapper.OrdersMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示种子:3 客户 + 15 订单铺满退款四态机全部分支 + 生鲜/定制特殊品类。
 * 正常情况下客户由 mysql-init SQL 灌入;订单在此插入(表空时)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoSeedRunner implements ApplicationRunner {

    private final CustomerMapper customerMapper;
    private final OrdersMapper ordersMapper;

    @Override
    public void run(ApplicationArguments args) {
        seedCustomers();
        seedOrders();
    }

    private void seedCustomers() {
        if (customerMapper.selectCount(null) > 0) {
            return;
        }
        String[][] customers = {
                {"张小明", "13800000001"},
                {"李婷", "13800000002"},
                {"王芳", "13800000003"},
        };
        for (String[] c : customers) {
            Customer customer = new Customer();
            customer.setName(c[0]);
            customer.setPhone(c[1]);
            customerMapper.insert(customer);
        }
        log.info("种子数据:已插入 {} 个演示客户", customers.length);
    }

    /** 15 笔订单:四态 + 生鲜/定制/关闭/已退款 分支全覆盖 */
    private void seedOrders() {
        if (ordersMapper.selectCount(null) > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        record O(long customerId, String orderNo, String product, String category,
                 double amount, String status, int signedDaysAgo) {}
        List<O> orders = List.of(
                // 张小明
                new O(1, "2025001", "保温杯", "GENERAL", 199.00, com.sht.order.OrderStatus.PAID.name(), -1),
                new O(1, "2025002", "运动鞋", "GENERAL", 399.00, com.sht.order.OrderStatus.SIGNED.name(), 2),
                new O(1, "2025003", "蓝牙耳机", "GENERAL", 299.00, com.sht.order.OrderStatus.SHIPPED.name(), -1),
                new O(1, "2025004", "手机壳", "GENERAL", 39.00, com.sht.order.OrderStatus.CLOSED.name(), -1),
                new O(1, "2025005", "牛仔裤", "GENERAL", 259.00, com.sht.order.OrderStatus.SIGNED.name(), 10),
                // 李婷
                new O(2, "2025006", "香薰蜡烛", "GENERAL", 89.00, com.sht.order.OrderStatus.PAID.name(), -1),
                new O(2, "2025007", "智能电风扇", "GENERAL", 559.00, com.sht.order.OrderStatus.SHIPPED.name(), -1),
                new O(2, "2025008", "双肩背包", "GENERAL", 329.00, com.sht.order.OrderStatus.SIGNED.name(), 5),
                new O(2, "2025009", "护眼台灯", "GENERAL", 129.00, com.sht.order.OrderStatus.REFUNDED.name(), 8),
                new O(2, "2025010", "编织袋", "GENERAL", 25.00, com.sht.order.OrderStatus.PAID.name(), -1),
                // 王芳
                new O(3, "2025011", "机械键盘", "GENERAL", 599.00, com.sht.order.OrderStatus.SIGNED.name(), 20),
                new O(3, "2025012", "无线鼠标", "GENERAL", 199.00, com.sht.order.OrderStatus.SHIPPED.name(), -1),
                new O(3, "2025013", "生鲜水果礼盒", "FRESH", 158.00, com.sht.order.OrderStatus.SIGNED.name(), 1),
                new O(3, "2025014", "定制刻字钢笔", "CUSTOM", 268.00, com.sht.order.OrderStatus.PAID.name(), -1),
                new O(3, "2025015", "玻璃水杯", "GENERAL", 69.00, com.sht.order.OrderStatus.SIGNED.name(), 3),
                // 李婷再补一笔:签收第 7 天边界
                new O(2, "2025016", "陶瓷马克杯", "GENERAL", 45.00, com.sht.order.OrderStatus.SIGNED.name(), 7)
        );
        for (O o : orders) {
            Orders order = new Orders();
            order.setCustomerId(o.customerId());
            order.setOrderNo(o.orderNo());
            order.setProductName(o.product());
            order.setCategory(o.category());
            order.setAmount(java.math.BigDecimal.valueOf(o.amount()));
            order.setStatus(o.status());
            order.setSignedAt(o.signedDaysAgo() < 0 ? null : now.minusDays(o.signedDaysAgo()));
            order.setCreatedAt(now.minusDays(Math.max(1, Math.abs(o.signedDaysAgo()) + 3)));
            ordersMapper.insert(order);
        }
        log.info("种子数据:已插入 {} 笔订单(铺满退款四态与特殊品类)", orders.size());
    }
}
