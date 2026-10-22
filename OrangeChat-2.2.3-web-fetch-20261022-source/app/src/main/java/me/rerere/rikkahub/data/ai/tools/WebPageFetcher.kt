/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Direct HTTP page reader; never uses a search provider or executes page scripts. */
class WebPageFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    data class Page(
        val url: String,
        val statusCode: Int,
        val title: String,
        val contentType: String,
        val content: String,
        val contentLength: Int,
        val truncated: Boolean,
    )

    suspend fun fetch(url: String, maxLength: Int = 10_000, raw: Boolean = false): Page {
        require(maxLength in 1..50_000) { "max_length must be between 1 and 50000" }
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("url must be an absolute HTTP or HTTPS URL")
        require(httpUrl.username.isEmpty() && httpUrl.password.isEmpty()) {
            "URLs containing credentials are not supported"
        }
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "Mozilla/5.0 (Android) OrangeChat/1.0")
            .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json,application/xml,text/*;q=0.9")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val page = response.use { readPage(it, maxLength, raw) }
                        if (continuation.isActive) continuation.resume(page)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun readPage(response: Response, maxLength: Int, raw: Boolean): Page {
        require(response.isSuccessful) { "HTTP ${response.code}: ${response.message}" }
        val body = response.body
        val mediaType = body.contentType()
        val contentType = mediaType?.let { "${it.type}/${it.subtype}" } ?: "text/html"
        require(contentType.startsWith("text/") || contentType in TEXT_APPLICATION_TYPES ||
            contentType.endsWith("+json") || contentType.endsWith("+xml")) {
            "Unsupported content type: $contentType. Only web pages and text documents can be read."
        }
        require(body.contentLength() <= MAX_BODY_BYTES) { "Page exceeds the 2 MiB download limit" }
        val source = body.source()
        // request() also limits decompressed/chunked responses whose length is not known in advance.
        require(!source.request(MAX_BODY_BYTES + 1)) { "Page exceeds the 2 MiB download limit" }
        val bytes = source.readByteArray()
        val finalUrl = response.request.url.toString()
        val isHtml = contentType == "text/html" || contentType == "application/xhtml+xml"
        val document = if (isHtml) {
            Jsoup.parse(ByteArrayInputStream(bytes), mediaType?.charset()?.name(), finalUrl)
        } else null
        val title = document?.title().orEmpty()
        val text = if (raw || document == null) {
            bytes.toString(mediaType?.charset() ?: Charsets.UTF_8)
        } else {
            document.select("script,style,noscript,svg,head,nav,footer,[hidden],[aria-hidden=true]").remove()
            val main = document.selectFirst("main") ?: document.selectFirst("article") ?: document.body()
            main.select("p,div,section,article,h1,h2,h3,h4,h5,h6,li,tr,pre,blockquote")
                .forEach { it.prependText("\n") }
            main.select("br").forEach { it.appendText("\n") }
            main.wholeText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }
        require(text.isNotBlank()) {
            "No readable content found. This page may require JavaScript rendering or login."
        }
        return Page(
            url = finalUrl,
            statusCode = response.code,
            title = title,
            contentType = contentType,
            content = text.take(maxLength),
            contentLength = text.length,
            truncated = text.length > maxLength,
        )
    }

    private companion object {
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        val TEXT_APPLICATION_TYPES = setOf("application/xhtml+xml", "application/json", "application/xml")
    }
}
