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
}
