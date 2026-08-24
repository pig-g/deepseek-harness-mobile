package com.labteto.dshmobile.ui.components

import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript must let users select/copy text and open URLs by tapping. These pin the
 * link-derivation half of that: markdown `[text](url)` links and bare `https://…` URLs both
 * become link spans with the right URL and the right character range.
 */
class MarkdownLinkifyTest {

    private val linkStyle = SpanStyle()

    // ---- linkifyUrls (user bubbles: raw text, bare URLs only) ----------------

    @Test
    fun `a bare url mid-sentence becomes one link without the trailing period`() {
        val result = linkifyUrls("See https://example.com for details.", linkStyle)
        assertEquals(1, result.links.size)
        assertEquals("https://example.com", result.links[0].url)
        // the range must cover exactly the URL inside the unchanged text
        assertEquals("See https://example.com for details.", result.text.text)
        assertEquals(
            result.text.text.substring(result.links[0].range.start, result.links[0].range.end),
            "https://example.com",
        )
    }

    @Test
    fun `a url at the end of the text is kept whole`() {
        val result = linkifyUrls("Docs: https://example.org/docs?ref=42", linkStyle)
        assertEquals("https://example.org/docs?ref=42", result.links.single().url)
    }

    @Test
    fun `a url wrapped in parens drops the unbalanced closing paren`() {
        val result = linkifyUrls("(see https://a.b)", linkStyle)
        assertEquals("https://a.b", result.links.single().url)
    }

    @Test
    fun `a balanced paren in the url is kept`() {
        val result = linkifyUrls("https://en.wikipedia.org/wiki/Foo_(bar)", linkStyle)
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", result.links.single().url)
    }

    @Test
    fun `two urls both become links`() {
        val result = linkifyUrls("https://a.b and https://c.d", linkStyle)
        assertEquals(listOf("https://a.b", "https://c.d"), result.links.map { it.url })
    }

    @Test
    fun `a token glued in front of the scheme is not a url`() {
        val result = linkifyUrls("foohttps://a.b", linkStyle)
        assertTrue(result.links.isEmpty())
        assertEquals("foohttps://a.b", result.text.text)
    }

    @Test
    fun `a scheme without host is not a url`() {
        val result = linkifyUrls("broken http:// end", linkStyle)
        assertTrue(result.links.isEmpty())
        assertEquals("broken http:// end", result.text.text)
    }

    // ---- parseInlineSegments (markdown: [text](url) plus bare urls) -----------

    @Test
    fun `a markdown link keeps its display text and target url`() {
        val segments = parseInlineSegments("read the [Docs](https://docs.dsh.dev) now")
        val link = segments.filterIsInstance<InlineSegment.Link>().single()
        assertEquals("Docs", link.text)
        assertEquals("https://docs.dsh.dev", link.url)
    }

    @Test
    fun `a bare url in a paragraph becomes a link with itself as text`() {
        val segments = parseInlineSegments("check https://a.b/c?x=1. today")
        val link = segments.filterIsInstance<InlineSegment.Link>().single()
        assertEquals("https://a.b/c?x=1", link.url)
        assertEquals(link.url, link.text)
    }

    @Test
    fun `a url inside an inline code span stays code, not a link`() {
        val segments = parseInlineSegments("run `curl https://a.b`")
        assertTrue(segments.filterIsInstance<InlineSegment.Link>().isEmpty())
        assertEquals("curl https://a.b", segments.filterIsInstance<InlineSegment.Code>().single().text)
    }
}
