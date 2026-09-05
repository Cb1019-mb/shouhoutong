package com.sht.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.settings.entity.AppSetting;
import com.sht.settings.mapper.AppSettingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时设置:键值存储,yml 默认值兜底。
 * 所有键的合法取值在 {@link SettingKey} 中定义;写入经 API 校验。
 */
@Slf4j
@Service
public class SettingsService {

    private final AppSettingMapper mapper;
    /** 读缓存:写入时失效,避免每次工具调用查库 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SettingsService(AppSettingMapper mapper) {
        this.mapper = mapper;
    }

    public String get(SettingKey key) {
        return cache.computeIfAbsent(key.key(), k -> {
            AppSetting row = mapper.selectOne(new LambdaQueryWrapper<AppSetting>()
                    .eq(AppSetting::getSettingKey, k));
            return row == null ? key.defaultValue() : row.getSettingValue();
        });
    }

    public double getDouble(SettingKey key) {
        return Double.parseDouble(get(key));
    }

    public int getInt(SettingKey key) {
        return Integer.parseInt(get(key));
    }

    /** 校验合法后写入,并失效缓存;返回错误信息,null=成功 */
    public String set(String key, String value) {
        SettingKey def = SettingKey.of(key);
        if (def == null) {
            return "未知的设置项: " + key;
        }
        String error = def.validate(value);
        if (error != null) {
            return error;
        }
        AppSetting row = mapper.selectOne(new LambdaQueryWrapper<AppSetting>()
                .eq(AppSetting::getSettingKey, key));
        if (row == null) {
            row = new AppSetting();
            row.setSettingKey(key);
            row.setSettingValue(value);
            row.setUpdatedAt(LocalDateTime.now());
            mapper.insert(row);
        } else {
            row.setSettingValue(value);
            mapper.updateById(row);
        }
        cache.remove(key);
        log.info("设置更新: {} = {}", key, value);
        return null;
    }

    /** 全部已知键的当前值(设置页回显) */
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        for (SettingKey key : SettingKey.values()) {
            out.put(key.key(), get(key));
        }
        return out;
    }
}
