package com.sht.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 思考块过滤器:兼容服务端带/不带 --reasoning-parser 两种部署 */
class ThinkFilterTest {

    @Test
    @DisplayName("服务端已分离思考(content 干净)→ 原样透传")
    void parserActive_passthrough() {
        ThinkFilter f = new ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String t : new String[]{"订单", "2025003", "物流", "正常"}) {
            out.append(f.accept(t));
        }
        assertThat(out.toString()).isEqualTo("订单2025003物流正常");
    }

    @Test
    @DisplayName("服务端未分离 → 思考块整体剥离")
    void thinkBlockStripped() {
        ThinkFilter f = new ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String t : new String[]{"<think>", "用户想退货", "需要查订单", "</think>", "好的", "帮您查"}) {
            out.append(f.accept(t));
        }
        assertThat(out.toString()).isEqualTo("好的帮您查");
    }

    @Test
    @DisplayName("关闭标签跨 token 拆分不泄漏")
    void closeTagSplitAcrossTokens() {
        ThinkFilter f = new ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String t : new String[]{"<think>", "abc", "</thi", "nk>", "后续回复"}) {
            out.append(f.accept(t));
        }
        assertThat(out.toString()).isEqualTo("后续回复");
    }

    @Test
    @DisplayName("开始标签跨 token 拆分不泄漏")
    void openTagSplitAcrossTokens() {
        ThinkFilter f = new ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String t : new String[]{"<th", "ink>", "思考中", "</think>", "正式回答"}) {
            out.append(f.accept(t));
        }
        assertThat(out.toString()).isEqualTo("正式回答");
    }

    @Test
    @DisplayName("前导空白后出现思考块也能识别")
    void leadingWhitespaceBeforeThink() {
        ThinkFilter f = new ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String t : new String[]{"\n", "<think>x</think>", "回复"}) {
            out.append(f.accept(t));
        }
        assertThat(out.toString()).isEqualTo("回复");
    }

    @Test
    @DisplayName("正文本身包含 < 开头的内容不受影响")
    void angleBracketInContent() {
        ThinkFilter f = new ThinkFilter();
        StringBuilder out = new StringBuilder();
        for (String t : new String[]{"<" , "订单> 已发货"}) {
            out.append(f.accept(t));
        }
        assertThat(out.toString()).isEqualTo("<订单> 已发货");
    }

    @Test
    @DisplayName("strip 兜底:完整文本剥离含未闭合残缺块")
    void stripFallback() {
        assertThat(ThinkFilter.strip("<think>abc</think>\n\n正式回答")).isEqualTo("正式回答");
        assertThat(ThinkFilter.strip("<think>被截断的回答")).isEmpty();
        assertThat(ThinkFilter.strip("干净文本")).isEqualTo("干净文本");
    }
}
