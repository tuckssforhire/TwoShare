package com.twoshare.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun pureEmojiCount(value:String):Int {
    val text=value.trim()
    if(text.isEmpty()) return 0
    val codePoints=text.codePoints().toArray()
    var count=0
    for(cp in codePoints){
        if(Character.isWhitespace(cp) || cp==0xFE0F || cp==0x200D || cp in 0x1F3FB..0x1F3FF || cp==0x20E3) continue
        val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF || cp==0x00A9 || cp==0x00AE || cp==0x203C || cp==0x2049 || cp==0x2122 || cp==0x2139 || cp in 0x2190..0x21FF
        if(!emoji) return 0
        count++
    }
    return count
}

internal fun absoluteTime(value:String):String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, h:mm a")
        .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
