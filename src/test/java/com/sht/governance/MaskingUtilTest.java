package com.sht.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计参数脱敏 */
class MaskingUtilTest {

    @Test
    @DisplayName("手机号中间 4 位打码")
    void phoneMasked() {
        String out = MaskingUtil.mask("{\"phone\":\"13812345678\"}");
        assertThat(out).isEqualTo("{\"phone\":\"138****5678\"}");
    }

    @Test
    @DisplayName("密码字段完全打码")
    void passwordMasked() {
        String out = MaskingUtil.mask("{\"password\":\"s3cret\",\"other\":1}");
        assertThat(out).contains("\"***\"");
        assertThat(out).doesNotContain("s3cret");
    }

    @Test
    @DisplayName("地址仅保留前 8 字符")
    void addressMasked() {
        String out = MaskingUtil.mask("{\"address\":\"广东省深圳市南山区科技园路 1 号 101 室\"}");
        assertThat(out).startsWith("{\"address\":\"广东省深圳市南山");   // 保留前 8 个字符
        assertThat(out).contains("***");
        assertThat(out).doesNotContain("科技园路");
    }
}
