package com.sht.settings;

/**
 * 设置项定义:键名 / 默认值 / 校验规则。
 * 新增设置项只需在此加枚举,API 校验与回显自动生效。
 */
public enum SettingKey {

    MODEL_TEMPERATURE("model.temperature", "0", v -> {
        try {
            double d = Double.parseDouble(v);
            return d < 0 || d > 2 ? "temperature 取值 0~2" : null;
        } catch (NumberFormatException e) {
            return "temperature 必须是数字";
        }
    }),

    CHAT_MAX_HISTORY("chat.maxHistory", "20", v -> {
        try {
            int n = Integer.parseInt(v);
            return n < 2 || n > 100 ? "历史轮数取值 2~100 条" : null;
        } catch (NumberFormatException e) {
            return "历史轮数必须是整数";
        }
    }),

    CHAT_STYLE_ADDON("chat.styleAddon", "", v -> {
        return v.length() > 300 ? "语气附加说明不超过 300 字" : null;
    }),

    RULE_FORCE_APPROVAL_OVER("rule.forceApprovalOver", "500", v -> {
        try {
            double d = Double.parseDouble(v);
            return d <= 0 ? "强制人工阈值必须大于 0" : null;
        } catch (NumberFormatException e) {
            return "阈值必须是数字";
        }
    }),

    RULE_COUPON_DAILY_LIMIT("rule.couponDailyLimit", "1", v -> {
        try {
            int n = Integer.parseInt(v);
            return n < 1 || n > 100 ? "券每日限额取值 1~100" : null;
        } catch (NumberFormatException e) {
            return "限额必须是整数";
        }
    }),

    RULE_REFUND_REPEAT_HOURS("rule.refundRepeatHours", "24", v -> {
        try {
            int n = Integer.parseInt(v);
            return n < 1 || n > 720 ? "防重窗口取值 1~720 小时" : null;
        } catch (NumberFormatException e) {
            return "防重窗口必须是整数";
        }
    }),

    RULE_EXPIRE_MINUTES("rule.expireMinutes", "1440", v -> {
        try {
            int n = Integer.parseInt(v);
            return n < 1 || n > 10080 ? "审批过期时间取值 1~10080 分钟" : null;
        } catch (NumberFormatException e) {
            return "过期时间必须是整数";
        }
    });

    private final String key;
    private final String defaultValue;
    private final java.util.function.Function<String, String> validator;

    SettingKey(String key, String defaultValue, java.util.function.Function<String, String> validator) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.validator = validator;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    /** 返回 null=合法,否则为错误信息 */
    public String validate(String value) {
        return value == null ? "值不能为空" : validator.apply(value);
    }

    public static SettingKey of(String key) {
        for (SettingKey k : values()) {
            if (k.key.equals(key)) {
                return k;
            }
        }
        return null;
    }
}
