package com.codefluence.sdk

import java.net.HttpURLConnection
import java.net.URL

/**
 * Default [CodefluenceTransport] backed by `HttpURLConnection` with the same
 * 5s connect/read timeouts the SDK has always used. A non-2xx status is read
 * from the error stream (if any) and returned as a response; a thrown
 * [java.io.IOException] (transport failure) propagates so the caller classifies
 * it as retryable.
 */
internal class SystemHttpTransport : CodefluenceTransport {
    override fun send(url: String, headers: Map<String, String>, body: String): CodefluenceHttpResponse = exchange(url, headers, body)

    override fun get(url: String, headers: Map<String, String>): CodefluenceHttpResponse = exchange(url, headers, null)

    private fun exchange(url: String, headers: Map<String, String>, body: String?): CodefluenceHttpResponse {
        var connection: HttpURLConnection? = null
        try {
            val request = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = if (body == null) "GET" else "POST"
                doOutput = body != null
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection = request
            if (body != null) request.outputStream.use { it.write(body.toByteArray()) }
            val status = request.responseCode
            val stream = if (status in 200..299) request.inputStream else request.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return CodefluenceHttpResponse(status, text)
        } finally {
            connection?.disconnect()
        }
    }
}
