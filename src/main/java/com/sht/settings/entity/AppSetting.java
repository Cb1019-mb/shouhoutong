package com.sht.settings.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_setting")
public class AppSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String settingKey;

    private String settingValue;

    private LocalDateTime updatedAt;
}
