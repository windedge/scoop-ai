package com.scoopai.tools.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdkHttpWebFetcherTest {

    private val fetcher = JdkHttpWebFetcher()

    // ---------------------------------------------------------------
    // extractMainContent — HTML noise removal
    // ---------------------------------------------------------------

    @Test
    fun `extractMainContent removes script blocks`() {
        val html = """
            <html>
            <head><script>alert('hello');</script></head>
            <body>Hello World</body>
            </html>
        """.trimIndent()
        val text = fetcher.extractMainContent(html)
        assertTrue(text.contains("Hello World"), "Should keep visible text")
        assertTrue(!text.contains("alert"), "Should remove script content")
    }

    @Test
    fun `extractMainContent removes style blocks`() {
        val html = """
            <html><head><style>body { color: red; }</style></head>
            <body>Content here</body>
            </html>
        """.trimIndent()
        val text = fetcher.extractMainContent(html)
        assertTrue(text.contains("Content here"), "Should keep visible text")
        assertTrue(!text.contains("color: red"), "Should remove style content")
    }

    @Test
    fun `extractMainContent removes noscript blocks`() {
        val html = """
            <html><body>
            <noscript>Please enable JavaScript</noscript>
            Visible
            </body></html>
        """.trimIndent()
        val text = fetcher.extractMainContent(html)
        assertTrue(text.contains("Visible"), "Should keep visible text")
        assertTrue(!text.contains("Please enable JavaScript"), "Should remove noscript content")
    }

    @Test
    fun `extractMainContent removes HTML comments`() {
        val html = """
            <html><body>
            <!-- This is a comment -->
            Hello
            </body></html>
        """.trimIndent()
        val text = fetcher.extractMainContent(html)
        assertTrue(text.contains("Hello"), "Should keep visible text")
        assertTrue(!text.contains("comment"), "Should remove comments")
    }

    @Test
    fun `extractMainContent strips HTML tags preserving text`() {
        val html = "<p>Hello <b>World</b></p>"
        val text = fetcher.extractMainContent(html)
        assertEquals("Hello World", text)
    }

    @Test
    fun `extractMainContent decodes HTML entities`() {
        val html = "<p>AT&amp;T &lt;test&gt; &quot;quote&quot; &amp; &apos;apos&apos;</p>"
        val text = fetcher.extractMainContent(html)
        assertTrue(text.contains("AT&T"), "Should decode &amp;")
        assertTrue(text.contains("<test>"), "Should decode &lt; and &gt;")
    }

    @Test
    fun `extractMainContent collapses whitespace`() {
        val html = "<div>  Hello    World  </div>"
        val text = fetcher.extractMainContent(html)
        assertEquals("Hello World", text)
    }

    @Test
    fun `extractMainContent handles empty string`() {
        assertEquals("", fetcher.extractMainContent(""))
    }

    @Test
    fun `extractMainContent handles text without HTML tags`() {
        assertEquals("Hello World", fetcher.extractMainContent("Hello World"))
    }

    // ---------------------------------------------------------------
    // HTML detection for fetchWebPage
    // ---------------------------------------------------------------

    @Test
    fun `extractMainContent passes through JSON unchanged`() {
        val json = """{"name":"test","version":"1.0"}"""
        assertEquals(json, fetcher.extractMainContent(json))
    }
}
