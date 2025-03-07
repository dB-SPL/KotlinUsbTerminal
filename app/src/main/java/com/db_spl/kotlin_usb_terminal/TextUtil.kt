package com.db_spl.kotlin_usb_terminal

import android.text.*
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import androidx.annotation.ColorInt
import java.io.ByteArrayOutputStream

/**
 * A collection of text and byte-array utilities,
 * including conversions to/from hex strings,
 * caret notation, etc.
 */
object TextUtil {
    @ColorInt
    var caretBackground = 0xff666666.toInt()

    const val newline_crlf = "\r\n"
    const val newline_lf = "\n"

    @JvmStatic
    fun fromHexString(s: CharSequence): ByteArray {
        val buf = ByteArrayOutputStream()
        var b: Byte = 0
        var nibble = 0
        for (element in s) {
            if (nibble == 2) {
                buf.write(b.toInt())
                nibble = 0
                b = 0
            }
            when {
                element in '0'..'9' -> {
                    nibble++
                    b = (b.toInt() * 16 + (element.code - '0'.code)).toByte()
                }
                element in 'A'..'F' -> {
                    nibble++
                    b = (b.toInt() * 16 + (element.code - 'A'.code + 10)).toByte()
                }
                element in 'a'..'f' -> {
                    nibble++
                    b = (b.toInt() * 16 + (element.code - 'a'.code + 10)).toByte()
                }
            }
        }
        if (nibble > 0) {
            buf.write(b.toInt())
        }
        return buf.toByteArray()
    }

    @JvmStatic
    fun toHexString(buf: ByteArray): String {
        return toHexString(buf, 0, buf.size)
    }

    @JvmStatic
    fun toHexString(buf: ByteArray, begin: Int, end: Int): String {
        val sb = StringBuilder(3 * (end - begin))
        toHexString(sb, buf, begin, end)
        return sb.toString()
    }

    @JvmStatic
    fun toHexString(sb: StringBuilder, buf: ByteArray) {
        toHexString(sb, buf, 0, buf.size)
    }

    @JvmStatic
    fun toHexString(sb: StringBuilder, buf: ByteArray, begin: Int, end: Int) {
        for (pos in begin until end) {
            if (sb.isNotEmpty()) sb.append(' ')
            var c = (buf[pos].toInt() and 0xFF) / 16
            c += if (c >= 10) 'A'.code - 10 else '0'.code
            sb.append(c.toChar())

            c = (buf[pos].toInt() and 0xFF) % 16
            c += if (c >= 10) 'A'.code - 10 else '0'.code
            sb.append(c.toChar())
        }
    }

    /**
     * Use https://en.wikipedia.org/wiki/Caret_notation to avoid invisible control characters
     */
    @JvmStatic
    fun toCaretString(s: CharSequence, keepNewline: Boolean): CharSequence {
        return toCaretString(s, keepNewline, s.length)
    }

    @JvmStatic
    fun toCaretString(s: CharSequence, keepNewline: Boolean, length: Int): CharSequence {
        var found = false
        for (pos in 0 until length) {
            if (s[pos] < ' '.also { /* ASCII 32 */ } && (!keepNewline || s[pos] != '\n')) {
                found = true
                break
            }
        }
        if (!found) {
            return s
        }
        val sb = SpannableStringBuilder()
        for (pos in 0 until length) {
            if (s[pos] < ' ' && (!keepNewline || s[pos] != '\n')) {
                sb.append('^')
                sb.append((s[pos].code + 64).toChar())
                sb.setSpan(
                    BackgroundColorSpan(caretBackground),
                    sb.length - 2,
                    sb.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                sb.append(s[pos])
            }
        }
        return sb
    }

    class HexWatcher(private val view: TextView) : TextWatcher {
        private val sb = StringBuilder()
        private var self = false
        private var enabled = false

        fun enable(enable: Boolean) {
            if (enable) {
                view.inputType = InputType.TYPE_CLASS_TEXT or
                                 InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                view.inputType = InputType.TYPE_CLASS_TEXT or
                                 InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            enabled = enable
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (!enabled || self) return

            sb.delete(0, sb.length)
            for (element in s) {
                when {
                    element in '0'..'9' -> sb.append(element)
                    element in 'A'..'F' -> sb.append(element)
                    element in 'a'..'f' -> {
                        // convert to uppercase hex
                        sb.append((element.code + ('A'.code - 'a'.code)).toChar())
                    }
                }
            }
            // insert a space after every 2 hex digits
            var i = 2
            while (i < sb.length) {
                sb.insert(i, ' ')
                i += 3
            }
            val s2 = sb.toString()

            if (s2 != s.toString()) {
                self = true
                s.replace(0, s.length, s2)
                self = false
            }
        }
    }
}
