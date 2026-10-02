package com.mt.webnest

import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class PhaseTwoServer : AutoCloseable {
    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    val download = "WebNest authenticated download".toByteArray()
    private val worker =
        thread(isDaemon = true) {
            while (running.get()) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: return@use
                    val headers = buildString {
                        appendLine(first)
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                            appendLine(line)
                        }
                    }
                    requests.add(headers)
                    val path = first.split(' ').getOrElse(1) { "/" }
                    val body =
                        if (path == "/download") download
                        else
                            (if (path == "/popup")
                                    """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sign in</title></head>
                    <body><button id="finish" onclick="window.opener.postMessage('signed-in', '*'); window.close()">Finish login</button></body></html>
                """
                                else
                                    """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Phase two</title></head>
                    <body><a id="popup" href="/popup" target="_blank" rel="opener">Sign in</a>
                    <input id="upload" type="file" multiple accept="text/plain">
                    <p><input id="capture" type="file" accept="image/*" capture="environment"></p>
                    <script>
                    window.addEventListener('message', e => { if(e.origin === location.origin && e.data === 'signed-in') document.title='Signed in'; });
                    document.querySelector('#capture').onchange = e => { document.title = 'Captured:' + e.target.files[0].size; };
                    document.querySelector('#upload').onchange = async e => { document.title = await e.target.files[0].text(); };
                    </script></body></html>
                """)
                                .toByteArray()
                    val type =
                        if (path == "/download") "application/octet-stream"
                        else "text/html; charset=utf-8"
                    val extra =
                        if (path == "/download")
                            "Content-Disposition: attachment; filename=phase-two.txt\r\n"
                        else ""
                    socket
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n${extra}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray() + body
                        )
                }
            }
        }

    override fun close() {
        running.set(false)
        server.close()
        worker.join(1000)
    }
}
