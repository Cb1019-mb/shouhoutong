package com.sht.governance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个 LLM 工具方法,纳入治理切面:
 * 策略引擎裁决 → 放行执行 / 拒绝返回 / 审批挂起,并全量落审计。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Governed {

    /** 策略文件中的工具键,如 createRefund */
    String value();
}
