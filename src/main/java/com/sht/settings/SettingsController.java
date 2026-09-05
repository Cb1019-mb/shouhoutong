package com.sht.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 设置 API:读取全部(设置页回显)/ 批量写入(逐键校验,全部合法才提交) */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public Map<String, String> all() {
        return settingsService.all();
    }

    @PostMapping
    public Map<String, Object> update(@RequestBody Map<String, String> body) {
        // 先整体校验,任一非法则整批拒绝
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            SettingKey def = SettingKey.of(e.getKey());
            if (def == null) {
                errors.put(e.getKey(), "未知的设置项");
            } else {
                String err = def.validate(e.getValue());
                if (err != null) {
                    errors.put(e.getKey(), err);
                }
            }
        }
        if (!errors.isEmpty()) {
            return Map.of("status", "INVALID", "errors", errors);
        }
        for (Map.Entry<String, String> e : body.entrySet()) {
            settingsService.set(e.getKey(), e.getValue());
        }
        return Map.of("status", "OK");
    }
}
