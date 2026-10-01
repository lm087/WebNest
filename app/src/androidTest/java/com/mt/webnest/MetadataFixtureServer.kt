package com.mt.webnest

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

internal class MetadataFixtureServer : AutoCloseable {
    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    val paths = CopyOnWriteArrayList<String>()
    val icon = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, out); recycle()
        }
        out.toByteArray()
    }
    val ico = dibIcon()
    private val worker = thread(isDaemon = true) {
        while (running.get()) runCatching {
            server.accept().use { socket ->
                socket.soTimeout = 2000
                val reader = socket.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return@use
                while (!reader.readLine().isNullOrEmpty()) { }
                val path = first.split(' ')[1]
                paths.add(path)
                var status = "200 OK"
                var type = "text/html; charset=utf-8"
                var headers = ""
                var body = when (path) {
                    "/pwa" -> "<title>HTML title</title><meta name=theme-color content=#000000><link rel='manifest' href='/assets/app.webmanifest'>".toByteArray()
                    "/assets/app.webmanifest" -> {
                        type = "application/manifest+json"
                        """{"theme_color":"#336699","name":"Long PWA Name","short_name":"PWA short","icons":[{"src":"missing.png","sizes":"512x512"},{"src":"icon.png","sizes":"192x192"}]}""".toByteArray()
                    }
                    "/assets/icon.png" -> { type = "image/png"; icon }
                    "/fallback" -> "<title>Fallback &amp; title</title><meta name=theme-color content=#abcdef><link rel='manifest' href='/bad.webmanifest'>".toByteArray()
                    "/bad.webmanifest" -> "not valid JSON".toByteArray()
                    "/favicon.ico" -> { type = "image/x-icon"; ico }
                    else -> { status = "404 Not Found"; byteArrayOf() }
                }
                if (path == "/pwa") {
                    body = ByteArrayOutputStream().use { out -> GZIPOutputStream(out).use { it.write(body) }; out.toByteArray() }
                    headers = "Content-Encoding: gzip\r\n"
                }
                socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: $type\r\n${headers}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
            }
        }
    }
    override fun close() { running.set(false); server.close(); worker.join(1000) }

    companion object {
        fun dibIcon(): ByteArray = ByteBuffer.allocate(22 + 40 + 16 + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(1)
            put(2); put(2); put(0); put(0); putShort(1); putShort(24); putInt(64); putInt(22)
            putInt(40); putInt(2); putInt(4); putShort(1); putShort(24); putInt(0); putInt(16)
            putInt(0); putInt(0); putInt(0); putInt(0)
            put(byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 0, 0))
            put(byteArrayOf(0, 0, 255.toByte(), 0, 255.toByte(), 0, 0, 0))
            put(byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 0))
        }.array()
    }
}
