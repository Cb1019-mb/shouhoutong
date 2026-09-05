package com.sht.governance;

import java.util.regex.Pattern;

/**
 * 审计参数脱敏:手机号/地址/密码类字段在落审计前打码。
 */
public final class MaskingUtil {

    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    private static final Pattern PASSWORD_KEY = Pattern.compile(
            "(\"(?:password|passwd|pwd|token|secret)\"\\s*:\\s*\")([^\"]*)(\")");

    private MaskingUtil() {
    }

    public static String mask(String paramsJson) {
        if (paramsJson == null || paramsJson.isEmpty()) {
            return "";
        }
        String out = PHONE.matcher(paramsJson).replaceAll("$1****$2");
        out = PASSWORD_KEY.matcher(out).replaceAll("$1***$3");
        // 长地址打码:保留前 8 个字符
        out = maskAddressValues(out);
        return out;
    }

    private static String maskAddressValues(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        int i = 0;
        while (true) {
            int keyIdx = json.indexOf("\"address\"", i);
            if (keyIdx < 0) {
                sb.append(json, i, json.length());
                break;
            }
            int colon = json.indexOf(':', keyIdx);
            int open = json.indexOf('"', colon);
            int close = json.indexOf('"', open + 1);
            if (colon < 0 || open < 0 || close < 0) {
                sb.append(json, i, json.length());
                break;
            }
            sb.append(json, i, open + 1);
            String addr = json.substring(open + 1, close);
            sb.append(addr, 0, Math.min(8, addr.length())).append("***");
            i = close;
        }
        return sb.toString();
    }
}
