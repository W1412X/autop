package com.example.autop.core.common

/**
 * 密钥存储端口（docs/tech/02 §4.2）。API Key 只允许经本接口存取（任务书硬性规则 6）。
 *
 * `ref` 形如 `secret:model:<profileId>`。实现（core-platform 的 KeystoreSecretStore）
 * 用 Android Keystore 加密后落盘；密钥与明文禁止进入日志、配置导出、事件日志。
 */
public interface SecretStore {
    public suspend fun put(
        ref: String,
        secret: String,
    )

    public suspend fun get(ref: String): String?

    public suspend fun remove(ref: String)
}
