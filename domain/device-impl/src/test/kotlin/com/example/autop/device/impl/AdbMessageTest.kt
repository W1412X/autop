package com.example.autop.device.impl

import com.example.autop.device.impl.adb.AdbMessage
import com.example.autop.device.impl.adb.AdbProtocol.A_CLSE
import com.example.autop.device.impl.adb.AdbProtocol.A_CNXN
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 协议消息编解码（N199，搬运自 Shizuku）：24 字节小端头 + magic 取反 +
 * data_crc32 为字节和、字符串载荷 NUL 终止——配对/连接握手的对端兼容性依赖这些约定。
 */
class AdbMessageTest {
    @Test
    fun `header fields round-trip through little endian layout`() {
        val message = AdbMessage(A_CNXN, 0x01000000, 256 * 1024, "host::")

        val bytes = message.toByteArray()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(header.int).isEqualTo(A_CNXN)
        assertThat(header.int).isEqualTo(0x01000000)
        assertThat(header.int).isEqualTo(256 * 1024)
        assertThat(header.int).isEqualTo(message.data_length)
        assertThat(header.int).isEqualTo(message.data_crc32)
        assertThat(header.int).isEqualTo(message.magic)
        assertThat(message.validate()).isTrue()
    }

    @Test
    fun `string payload is nul terminated`() {
        val message = AdbMessage(A_CNXN, 0, 0, "host::")

        val payload = requireNotNull(message.data)
        assertThat(payload.size).isEqualTo("host::".toByteArray().size + 1)
        assertThat(payload.last()).isEqualTo(0.toByte())
    }

    @Test
    fun `magic is bitwise complement of command`() {
        val message = AdbMessage(A_CLSE, 1, 2, null as ByteArray?)

        assertThat(message.magic).isEqualTo(A_CLSE xor -0x1)
    }

    @Test
    fun `crc32 is sum of payload bytes`() {
        val message = AdbMessage(A_CNXN, 0, 0, byteArrayOf(1, 2, 255.toByte()))

        assertThat(message.data_crc32).isEqualTo(1 + 2 + 255)
    }

    @Test
    fun `validate rejects corrupted checksum`() {
        val bad = AdbMessage(A_CNXN, 0, 0, 3, 999, A_CNXN xor -0x1, byteArrayOf(1, 2, 3))

        assertThat(bad.validate()).isFalse()
    }

    @Test
    fun `empty payload message validates and serializes header only`() {
        val message = AdbMessage(A_CLSE, 1, 2, null as ByteArray?)

        assertThat(message.data_length).isEqualTo(0)
        assertThat(message.toByteArray()).hasLength(AdbMessage.HEADER_LENGTH)
        assertThat(message.validate()).isTrue()
    }
}
