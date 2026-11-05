/**
 * 日志文件端口（docs/tech/02 §4.4，questions.md N165）：持久化日志的追加、目录信息与清理
 * （进程崩溃后内存环形缓冲丢失，文件是唯一取证来源；追加仅由 Logger 调用）。
 */
package com.example.autop.core.common

/** 持久化日志文件的追加、查询与清理端口。 */
public interface LogFiles {
    /** 日志目录绝对路径（展示用）。 */
    public val directoryPath: String

    /** 追加一行日志（实现必须兜底吞掉 IO 错误，日志设施自身不得抛异常）。 */
    public fun append(line: String)

    /** 全部日志文件当前占用字节合计。 */
    public fun totalBytes(): Long

    /** 全部日志文件内容拼接（最旧文件在前）；IO 错误返回已读部分（"导出日志"读取用，N175）。 */
    public fun readAll(): String

    /** 清空全部日志文件；返回删除的字节数。 */
    public fun clear(): Long
}
