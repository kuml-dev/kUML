package dev.kuml.desktop.mcp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/** Parsed HTTP response from [RawHttp]. */
internal data class RawResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * Minimal raw-socket HTTP/1.1 client for the MCP server tests. `HttpClient`/`HttpURLConnection`
 * refuse to set a custom `Host` header and cannot send chunked bodies without Content-Length,
 * both of which the transport's checks need to be exercised with.
 */
internal object RawHttp {
    fun send(
        port: Int,
        method: String = "POST",
        path: String = "/mcp",
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
        chunked: Boolean = false,
        declaredLength: Int? = null,
    ): RawResponse {
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { socket ->
            socket.soTimeout = 10_000
            val head = StringBuilder("$method $path HTTP/1.1\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            when {
                chunked -> head.append("Transfer-Encoding: chunked\r\n")
                body.isNotEmpty() || declaredLength != null -> head.append("Content-Length: ${declaredLength ?: body.size}\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            val out = socket.getOutputStream()
            try {
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (chunked) {
                    out.write("${Integer.toHexString(body.size)}\r\n".toByteArray())
                    out.write(body)
                    out.write("\r\n0\r\n\r\n".toByteArray())
                } else {
                    out.write(body)
                }
                out.flush()
            } catch (_: IOException) {
                // Server may answer and close before reading everything -- still read its answer.
            }
            val buf = ByteArrayOutputStream()
            try {
                socket.getInputStream().copyTo(buf)
            } catch (_: IOException) {
                if (buf.size() == 0) throw IOException("no response")
            }
            return parse(buf.toByteArray())
        }
    }

    private fun parse(raw: ByteArray): RawResponse {
        val text = raw.toString(Charsets.UTF_8)
        val split = text.indexOf("\r\n\r\n")
        val head = if (split >= 0) text.substring(0, split) else text
        val body = if (split >= 0) text.substring(split + 4) else ""
        val lines = head.split("\r\n")
        val status = lines[0].split(" ")[1].toInt()
        val headers =
            lines
                .drop(1)
                .filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return RawResponse(status = status, headers = headers, body = body)
    }
}
