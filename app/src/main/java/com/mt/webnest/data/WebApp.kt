package com.mt.webnest.data

data class WebApp(
    val id: Long = 0,
    val name: String,
    val url: String,
    val icon: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long? = null,
    val desktopMode: Boolean = false,
    val userAgent: String = "",
    val thirdPartyCookies: Boolean = false,
    val modifiedAt: Long = createdAt,
    val pinned: Boolean = false,
    val position: Long = 0,
    val themeColor: Int? = null,
)