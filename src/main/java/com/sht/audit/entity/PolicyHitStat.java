package com.sht.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/** 看板计数与频控共用表 */
@Data
@TableName("policy_hit_stat")
public class PolicyHitStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleKey;

    private Integer hitCount;

    private LocalDate date;
}
