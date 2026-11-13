/**
 * 记忆领域错误码（docs/tech/06 §1）。
 */
package com.example.autop.memory

public object MemoryErrorCodes {
    public const val INVALID_PATH: String = "memory.invalid_path"
    public const val NOT_FOUND: String = "memory.not_found"
    public const val ALREADY_EXISTS: String = "memory.already_exists"
    public const val ROOT_PROTECTED: String = "memory.root_protected"
    public const val TOO_LARGE: String = "memory.too_large"
    public const val EMBEDDING_FAILED: String = "memory.embedding_failed"

    /** 同一目录下已有同名条目，agent 新建被拒绝（需 UPDATE 或 force_new）。 */
    public const val POSSIBLE_DUPLICATE: String = "memory.possible_duplicate"

    /** 字段不满足约束：关键词数量/长度、摘要长度、agent 新建缺少必填字段。 */
    public const val INVALID_FIELDS: String = "memory.invalid_fields"
}
