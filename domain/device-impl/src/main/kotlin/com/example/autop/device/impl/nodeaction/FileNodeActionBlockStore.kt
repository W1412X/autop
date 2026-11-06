/**
 * [NodeActionBlockStore] 的文件实现（ADR-0015）：`files/device/node_action_blocks.json`。
 * 文件顶层带 `format` + `version`，只增不改；写入先落临时文件再原子替换。
 */
package com.example.autop.device.impl.nodeaction

import android.content.Context
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.NodeActionBlock
import com.example.autop.device.NodeActionBlockStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class BlockFile(
    @SerialName("format") val format: String = FORMAT,
    @SerialName("version") val version: Int = VERSION,
    @SerialName("blocks") val blocks: List<BlockEntry> = emptyList(),
)

@Serializable
private data class BlockEntry(
    @SerialName("packageName") val packageName: String,
    @SerialName("reason") val reason: String,
    @SerialName("blockedAt") val blockedAt: Long,
)

private const val FORMAT = "node_action_blocks"
private const val VERSION = 1
private const val TAG = "NodeActionBlocks"
private const val DIR = "device"
private const val FILE_NAME = "node_action_blocks.json"
private const val TMP_SUFFIX = ".tmp"

/** 文件持久化的屏蔽记录；内存缓存，首次访问时加载。 */
@Singleton
public class FileNodeActionBlockStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
        private val clock: Clock,
        private val configProvider: ConfigProvider,
        private val logger: Logger,
    ) : NodeActionBlockStore {
        private val mutex = Mutex()
        private var cache: MutableMap<String, NodeActionBlock>? = null

        override suspend fun isBlocked(packageName: String): Boolean =
            mutex.withLock { loaded()[packageName]?.let { !expired(it) } == true }

        override suspend fun block(
            packageName: String,
            reason: String,
        ) {
            mutex.withLock {
                val map = loaded()
                map[packageName] = NodeActionBlock(packageName, reason, clock.nowMillis())
                persist(map)
                logger.i(TAG, "node_action_blocked", "package" to packageName, "reason" to reason)
            }
        }

        override suspend fun unblock(packageName: String) {
            mutex.withLock {
                val map = loaded()
                if (map.remove(packageName) != null) persist(map)
            }
        }

        override suspend fun blocked(): List<NodeActionBlock> =
            mutex.withLock { loaded().values.filterNot { expired(it) }.sortedBy { it.packageName } }

        private fun expired(block: NodeActionBlock): Boolean {
            val ttlMs = TimeUnit.DAYS.toMillis(configProvider.current()[DeviceConfig.NODE_ACTION].blockTtlDays.toLong())
            return clock.nowMillis() - block.blockedAt > ttlMs
        }

        private suspend fun loaded(): MutableMap<String, NodeActionBlock> {
            cache?.let { return it }
            val read = withContext(dispatchers.io) { readFile() }
            return read.also { cache = it }
        }

        private fun readFile(): MutableMap<String, NodeActionBlock> {
            val file = file()
            if (!file.exists()) return mutableMapOf()
            return try {
                val parsed = AutopJson.default.decodeFromString(BlockFile.serializer(), file.readText())
                parsed.blocks
                    .associate { it.packageName to NodeActionBlock(it.packageName, it.reason, it.blockedAt) }
                    .toMutableMap()
            } catch (e: IOException) {
                logger.w(TAG, "node_action_blocks_read_failed", "error" to (e.message ?: "io"))
                mutableMapOf()
            } catch (e: SerializationException) {
                logger.w(TAG, "node_action_blocks_parse_failed", "error" to (e.message ?: "parse"))
                mutableMapOf()
            }
        }

        private suspend fun persist(map: Map<String, NodeActionBlock>) {
            withContext(dispatchers.io) {
                val body =
                    BlockFile(blocks = map.values.map { BlockEntry(it.packageName, it.reason, it.blockedAt) })
                val target = file()
                val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
                try {
                    target.parentFile?.mkdirs()
                    tmp.writeText(AutopJson.default.encodeToString(BlockFile.serializer(), body))
                    if (!tmp.renameTo(target)) {
                        target.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (e: IOException) {
                    logger.w(TAG, "node_action_blocks_write_failed", "error" to (e.message ?: "io"))
                }
            }
        }

        private fun file(): File = File(File(context.filesDir, DIR), FILE_NAME)
    }
