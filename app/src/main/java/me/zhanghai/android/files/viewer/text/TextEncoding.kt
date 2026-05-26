/*
 * Copyright (c) 2026 T HAN
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Lightweight text encodings supported by the built-in editor. */
data class TextEncoding(
    val id: String,
    val displayName: String,
    val charset: Charset,
    val writeBom: Boolean = false
) {
    fun decode(bytes: ByteArray): String {
        val content = when {
            id == UTF_8_BOM.id && bytes.startsWith(UTF8_BOM) -> bytes.copyOfRange(UTF8_BOM.size, bytes.size)
            id == UTF_16_LE.id && bytes.startsWith(UTF16_LE_BOM) -> bytes.copyOfRange(UTF16_LE_BOM.size, bytes.size)
            id == UTF_16_BE.id && bytes.startsWith(UTF16_BE_BOM) -> bytes.copyOfRange(UTF16_BE_BOM.size, bytes.size)
            else -> bytes
        }
        return charset.decode(ByteBuffer.wrap(content)).toString()
    }

    fun encode(text: String): ByteArray {
        val content = text.toByteArray(charset)
        val bom = when {
            id == UTF_8_BOM.id && writeBom -> UTF8_BOM
            id == UTF_16_LE.id && writeBom -> UTF16_LE_BOM
            id == UTF_16_BE.id && writeBom -> UTF16_BE_BOM
            else -> null
        }
        return if (bom != null) bom + content else content
    }

    companion object {
        val UTF_8 = TextEncoding("UTF-8", "UTF-8", StandardCharsets.UTF_8)
        val UTF_8_BOM = TextEncoding("UTF-8-BOM", "UTF-8 BOM", StandardCharsets.UTF_8, true)
        val GB18030 = TextEncoding("GB18030", "GBK / GB18030", Charset.forName("GB18030"))
        val UTF_16_LE = TextEncoding("UTF-16LE", "UTF-16LE", StandardCharsets.UTF_16LE, true)
        val UTF_16_BE = TextEncoding("UTF-16BE", "UTF-16BE", StandardCharsets.UTF_16BE, true)
        val ISO_8859_1 = TextEncoding("ISO-8859-1", "ISO-8859-1", StandardCharsets.ISO_8859_1)

        val VALUES = listOf(UTF_8, UTF_8_BOM, GB18030, UTF_16_LE, UTF_16_BE, ISO_8859_1)

        fun byId(id: String): TextEncoding = VALUES.first { it.id == id }

        fun detect(bytes: ByteArray): TextEncoding = when {
            bytes.startsWith(UTF8_BOM) -> UTF_8_BOM
            bytes.startsWith(UTF16_LE_BOM) -> UTF_16_LE
            bytes.startsWith(UTF16_BE_BOM) -> UTF_16_BE
            bytes.isValidUtf8() -> UTF_8
            else -> GB18030
        }
    }
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.isValidUtf8(): Boolean = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
    true
} catch (_: CharacterCodingException) {
    false
}
