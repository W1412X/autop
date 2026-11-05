package com.example.autop.core.common

/**
 * 厂商/ROM 适配规则查询（docs/tech/02 §4.3）。规则数据在
 * `core-platform/src/main/assets/rules/device_quirks.json`，核心代码禁止按 ROM 写分支
 * （任务书硬性规则 2：厂商判断进规则文件）。
 */
public interface DeviceQuirks {
    /** 所有匹配规则的 values 合并后取 [key]；后匹配的覆盖先匹配的，未命中返回 null。 */
    public fun value(key: String): String?
}
