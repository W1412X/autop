package com.example.autop

/**
 * 一次打开页面的请求。每次请求都是新实例，即使路由相同，导航效果也会重新触发（通知重复点击同一对话时需要）。
 */
internal class OpenRoute(
    val route: String,
)
