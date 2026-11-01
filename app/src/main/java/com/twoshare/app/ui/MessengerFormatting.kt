package com.twoshare.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun absoluteTime(v: String): String = runCatching {
    Instant.parse(v)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
}.getOrElse { v }

internal fun pureEmojiCount(s: String): Int {
    val x = s.trim()
    if (x.isEmpty()) return 0
    val cps = x.codePoints().toArray()
    if (cps.any { it in 0x30..0x39 || it in 0x41..0x5A || it in 0x61..0x7A }) return 0
    return cps.count { it > 0x7F }.coerceAtMost(9)
}
