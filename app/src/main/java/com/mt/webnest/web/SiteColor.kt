package com.mt.webnest.web

object SiteColor {
    fun parse(value: String?): Int? {
        val text = value?.trim()?.lowercase() ?: return null
        val hex = text.removePrefix("#")
        if (text.startsWith('#'))
            return runCatching {
                val expanded =
                    when (hex.length) {
                        3,
                        4 -> hex.map { "$it$it" }.joinToString("")
                        6,
                        8 -> hex
                        else -> return null
                    }
                if (expanded.length == 8 && expanded.takeLast(2) != "ff") return null
                (0xff000000L or expanded.take(6).toLong(16)).toInt()
            }
                .getOrNull()
        val named =
            mapOf(
                "black" to 0x000000,
                "white" to 0xffffff,
                "red" to 0xff0000,
                "green" to 0x008000,
                "blue" to 0x0000ff,
                "gray" to 0x808080,
                "grey" to 0x808080,
                "orange" to 0xffa500,
                "purple" to 0x800080,
                "navy" to 0x000080,
                "teal" to 0x008080,
                "yellow" to 0xffff00,
            )
        named[text]?.let {
            return (0xff000000L or it.toLong()).toInt()
        }
        if ((!text.startsWith("rgb(") && !text.startsWith("rgba(")) || !text.endsWith(')'))
            return null
        val parts = text.substringAfter('(').substringBefore(')').split(',').map(String::trim)
        if (parts.size !in 3..4 || (parts.size == 4 && parts[3].toDoubleOrNull() != 1.0))
            return null
        val channels =
            parts.take(3).map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return (0xff000000L or
                (channels[0].toLong() shl 16) or
                (channels[1].toLong() shl 8) or
                channels[2].toLong())
            .toInt()
    }
}
