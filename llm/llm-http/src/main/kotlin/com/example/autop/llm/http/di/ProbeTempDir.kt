package com.example.autop.llm.http.di

import javax.inject.Qualifier

/**
 * 限定符：测试连接内置图片的写入目录（app 装配为 `cacheDir`，见 app 的 `di/ProbeTempDirModule`）。
 * JVM 环境的 `java.io.tmpdir` 在 Android 上不可靠，故目录由装配方显式提供。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class ProbeTempDir
