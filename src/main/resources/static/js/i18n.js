/* 售后通 · 中文映射 + 审计 JSON 结构化渲染(审批中心/审计中心共用) */
window.SHT = {
    /* 订单状态 */
    status: {
        UNPAID: '待付款', PAID: '已付款', SHIPPED: '运输中', SIGNED: '已签收',
        REFUNDING: '退款中', REFUNDED: '已退款', CLOSED: '已关闭', CANCELLED: '已取消'
    },
    /* 审批任务状态 */
    taskStatus: {
        PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝',
        EXPIRED: '已过期', EXECUTE_FAILED: '执行失败'
    },
    /* 策略裁决 */
    decision: { ALLOW: '放行', DENY: '拦截', APPROVE: '待审批', EXPIRE: '过期' },
    /* 风险等级 */
    risk: { LOW: '低', MID: '中', HIGH: '高' },

    zh(map, key) {
        const k = (key || '').trim();
        return map[k] || k || '-';
    },

    /* 全站翻译通道:按 data-zh 标记批量替换文本 */
    translate(root) {
        (root || document).querySelectorAll('[data-zh]').forEach(el => {
            const map = SHT[el.dataset.zh];
            if (map) el.textContent = SHT.zh(map, el.textContent);
        });
    },

    /* 金额统一两位小数 */
    money(el) {
        const n = Number(el.textContent.replace(/[¥,\s]/g, ''));
        if (!Number.isNaN(n) && el.textContent.trim() !== '') {
            el.textContent = '¥' + n.toFixed(2);
        }
    },

    tryParse(text) {
        if (!text || (!text.startsWith('{') && !text.startsWith('['))) return null;
        try { return JSON.parse(text); } catch (e) { return null; }
    },

    /* 审计 JSON → 紧凑键值;reply_guidance 属内部指令,绝不外显 */
    jsonSummary(obj) {
        const HIDE = ['reply_guidance'];
        const parts = [];
        let extra = '';
        for (const [k, v] of Object.entries(obj)) {
            if (HIDE.includes(k)) { extra += (extra ? '\n' : '') + k + ': ' + v; continue; }
            const val = typeof v === 'object' ? JSON.stringify(v) : String(v);
            parts.push(k + '=' + (val.length > 40 ? val.slice(0, 40) + '…' : val));
        }
        return { main: parts.join(' · '), extra };
    }
};
