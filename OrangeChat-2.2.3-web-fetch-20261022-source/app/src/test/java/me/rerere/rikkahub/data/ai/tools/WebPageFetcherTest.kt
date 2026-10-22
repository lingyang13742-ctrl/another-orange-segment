/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebPageFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var fetcher: WebPageFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = WebPageFetcher()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `extracts title and readable main content without scripts or navigation`() = runBlocking {
        server.enqueue(html("""
            <title>文章 &amp; 文档</title><nav>菜单</nav><main>
            <h1>你好</h1><p>正文 &lt;示例&gt;</p><p>第二段<br>下一行</p>
            <script>dangerous()</script><style>secret{}</style><div hidden>隐藏内容</div>
            </main><footer>页脚</footer>
        """.trimIndent()))

        val page = fetcher.fetch(server.url("/article").toString())

        assertEquals("文章 & 文档", page.title)
        assertEquals("你好\n正文 <示例>\n第二段\n下一行", page.content)
        assertFalse(page.truncated)
        assertEquals("/article", server.takeRequest().path)
    }

    @Test
    fun `follows redirects and returns final URL`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(html("<p>Final content</p>"))

        val page = fetcher.fetch(server.url("/start").toString())

        assertEquals(server.url("/final").toString(), page.url)
        assertEquals(200, page.statusCode)
        assertEquals("Final content", page.content)
    }

    @Test
    fun `keeps JSON containing angle brackets intact and reports truncation`() = runBlocking {
        val json = """{"content":"<tag>keep this</tag>"}"""
        server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(json))

        val page = fetcher.fetch(server.url("/data").toString(), maxLength = 15)

        assertEquals(json.take(15), page.content)
        assertEquals(json.length, page.contentLength)
        assertTrue(page.truncated)
    }

    @Test
    fun `honors raw HTML option`() = runBlocking {
        val body = "<p>Text</p><script>code()</script>"
        server.enqueue(html(body))
        assertEquals(body, fetcher.fetch(server.url("/").toString(), raw = true).content)
    }

    @Test
    fun `detects HTML charset from meta tag`() = runBlocking {
        val bytes = "<meta charset=GBK><p>中文正文</p>".toByteArray(charset("GBK"))
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html").setBody(Buffer().write(bytes)))
        assertEquals("中文正文", fetcher.fetch(server.url("/").toString()).content)
    }

    @Test
    fun `rejects non HTTP URLs credentials and invalid length before making requests`() = runBlocking {
        for (url in listOf("file:///etc/passwd", "ftp://example.com", "not a URL", "https://user:pass@example.com")) {
            expectFailure { fetcher.fetch(url) }
        }
        for (length in listOf(-1, 0, 50001)) {
            expectFailure { fetcher.fetch(server.url("/").toString(), maxLength = length) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects HTTP errors binary content and empty pages`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        server.enqueue(MockResponse().addHeader("Content-Type", "application/pdf").setBody("%PDF"))
        server.enqueue(html("<script>renderPage()</script>"))

        assertTrue(expectFailure { fetcher.fetch(server.url("/").toString()) }.message!!.contains("403"))
        assertTrue(expectFailure { fetcher.fetch(server.url("/").toString()) }.message!!.contains("application/pdf"))
        assertTrue(expectFailure { fetcher.fetch(server.url("/").toString()) }.message!!.contains("No readable content"))
    }

    @Test
    fun `limits chunked and compressed responses after decompression`() = runBlocking {
        val oversized = "a".repeat(2 * 1024 * 1024 + 1)
        server.enqueue(MockResponse().addHeader("Content-Type", "text/plain").setChunkedBody(oversized, 8192))
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8(oversized) }
        server.enqueue(MockResponse().addHeader("Content-Type", "text/plain")
            .addHeader("Content-Encoding", "gzip").setBody(compressed))

        repeat(2) {
            assertTrue(expectFailure { fetcher.fetch(server.url("/").toString()) }.message!!.contains("2 MiB"))
        }
    }

    @Test
    fun `times out stalled requests`() = runBlocking {
        server.enqueue(html("<p>slow</p>").setBodyDelay(2, TimeUnit.SECONDS))
        val client = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
        val error = expectFailure { WebPageFetcher(client).fetch(server.url("/").toString()) }
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun `cancellation cancels underlying HTTP call`() = runBlocking {
        server.enqueue(html("<p>slow</p>").setBodyDelay(2, TimeUnit.SECONDS))
        val client = OkHttpClient()
        val result = async { WebPageFetcher(client).fetch(server.url("/").toString()) }
        while (server.requestCount == 0) delay(10)
        val call = client.dispatcher.runningCalls().single()
        result.cancel()
        result.join()
        assertTrue(call.isCanceled())
        assertTrue(result.isCancelled)
    }

    private fun html(body: String) = MockResponse().addHeader("Content-Type", "text/html").setBody(body)

    private suspend fun expectFailure(block: suspend () -> Unit): Exception {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e
        }
        throw AssertionError("Expected fetching to fail")
    }
}
