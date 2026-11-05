package com.example.autop.policy.core.di

import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyRuleType
import com.example.autop.policy.core.BuiltinRuleTypes
import com.example.autop.policy.core.DefaultPolicyEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * policy-core 的装配（docs/tech/10 WP5）：[PolicyEngine] 端口绑定与内置规则类型多绑定。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 ConfigModule 的 mangling 教训，见 questions.md N12）。
 */
@Module
@InstallIn(SingletonComponent::class)
public object PolicyModule {
    @Provides
    @Singleton
    public fun providePolicyEngine(impl: DefaultPolicyEngine): PolicyEngine = impl

    /** 内置规则类型（扩展点：其他模块可用 @IntoSet 追加自定义类型）。 */
    @Provides
    @Singleton
    public fun provideBuiltinRuleTypes(): Set<@JvmSuppressWildcards PolicyRuleType> = BuiltinRuleTypes.ALL.toSet()
}
