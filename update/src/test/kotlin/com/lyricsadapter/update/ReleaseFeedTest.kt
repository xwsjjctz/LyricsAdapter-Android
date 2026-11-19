package com.lyricsadapter.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseFeedTest {
    private fun version(text: String) = AppVersion.parse(text) ?: error("$text did not parse")

    /** What GitHub answered for this repository's releases on 2026-11-18, cut down to the fields read. */
    private val github = checkNotNull(javaClass.getResource("github-releases.json")).readText()

    private fun release(
        tag: String,
        preRelease: Boolean = false,
        draft: Boolean = false,
        assets: String = apk("LyricsAdapter-${tag.removePrefix("v")}-android.apk"),
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$preRelease,"html_url":"https://example.com/$tag","assets":[$assets]}"""

    private fun apk(
        name: String,
        url: String = "https://example.com/download/$name",
        size: Long = 1000,
        digest: String? = "sha256:" + "ab".repeat(32),
    ) = """{"name":"$name","size":$size,"browser_download_url":"$url","digest":${digest?.let { "\"$it\"" }}}"""

    private fun feed(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun `reads GitHub's release list`() {
        val releases = ReleaseFeed.parse(github)

        assertEquals(listOf("0.1.2", "0.1.1", "0.1.0"), releases.map { it.version.toString() })
        val newest = releases.first()
        assertEquals("v0.1.2", newest.tag)
        assertTrue(newest.preRelease)
        assertEquals("LyricsAdapter-0.1.2-android.apk", newest.apk.name)
        assertEquals("https://github.com/xwsjjctz/LyricsAdapter-Android/releases/download/v0.1.2/LyricsAdapter-0.1.2-android.apk", newest.apk.url)
        assertEquals(10_593_759, newest.apk.size)
        assertEquals("540b67ee33322494ffec4c880f1df421747e06202a16ae75ec9523754b0c098c", newest.apk.sha256)
        assertEquals("https://github.com/xwsjjctz/LyricsAdapter-Android/releases/tag/v0.1.2", newest.page)
    }

    @Test
    fun `leaves out drafts, tags that are not versions and releases without an APK`() {
        val releases = ReleaseFeed.parse(
            feed(
                release("v0.3.0", draft = true),
                release("nightly"),
                release("v0.2.0", assets = """{"name":"notes.txt","size":3,"browser_download_url":"https://example.com/notes.txt","digest":null}"""),
                release("v0.1.9", assets = ""),
                release("v0.1.8"),
            ),
        )

        assertEquals(listOf("0.1.8"), releases.map { it.version.toString() })
    }

    @Test
    fun `leaves out an APK without a usable address or size`() {
        val releases = ReleaseFeed.parse(
            feed(
                release("v0.4.0", assets = apk("a.apk", url = "")),
                release("v0.3.0", assets = apk("a.apk", size = 0)),
                release("v0.2.0", assets = apk("a.apk", size = -5)),
                release("v0.1.0", assets = apk("a.APK")),
            ),
        )

        assertEquals(listOf("0.1.0"), releases.map { it.version.toString() })
    }

    @Test
    fun `keeps a release whose APK has no digest, without one`() {
        val missing = ReleaseFeed.parse(feed(release("v0.1.0", assets = apk("a.apk", digest = null)))).single()
        val other = ReleaseFeed.parse(feed(release("v0.1.0", assets = apk("a.apk", digest = "md5:0123")))).single()
        val short = ReleaseFeed.parse(feed(release("v0.1.0", assets = apk("a.apk", digest = "sha256:abcd")))).single()

        assertNull(missing.apk.sha256)
        assertNull(other.apk.sha256)
        assertNull(short.apk.sha256)
    }

    @Test
    fun `reads the digest in lower case`() {
        val release = ReleaseFeed.parse(feed(release("v0.1.0", assets = apk("a.apk", digest = "sha256:" + "AB".repeat(32))))).single()

        assertEquals("ab".repeat(32), release.apk.sha256)
    }

    @Test
    fun `refuses an answer that is not a release list`() {
        listOf("", "not json", """{"message":"API rate limit exceeded"}""", "[1, 2]", "null").forEach { answer ->
            assertFailsWith<UpdateFeedException>(answer) { ReleaseFeed.parse(answer) }
        }
    }

    @Test
    fun `an empty list has no releases`() {
        assertEquals(emptyList(), ReleaseFeed.parse("[]"))
    }

    @Test
    fun `the update is the newest release above the installed version`() {
        val releases = ReleaseFeed.parse(github)

        assertEquals("0.1.2", ReleaseFeed.update(releases, version("0.1.0"))?.version.toString())
        assertEquals("0.1.2", ReleaseFeed.update(releases, version("0.1.1"))?.version.toString())
        assertNull(ReleaseFeed.update(releases, version("0.1.2")))
        assertNull(ReleaseFeed.update(releases, version("0.2.0")))
    }

    @Test
    fun `the newest is found whatever order the list comes in`() {
        val releases = ReleaseFeed.parse(feed(release("v0.1.9", preRelease = true), release("v0.1.10", preRelease = true), release("v0.1.2", preRelease = true)))

        assertEquals("0.1.10", ReleaseFeed.update(releases, version("0.1.2"))?.version.toString())
    }

    @Test
    fun `a full version is not offered pre-releases`() {
        val releases = ReleaseFeed.parse(feed(release("v1.2.0-beta.1", preRelease = true), release("v1.1.0"), release("v1.0.1", preRelease = true)))

        assertEquals("1.1.0", ReleaseFeed.update(releases, version("1.0.0"))?.version.toString())
        assertNull(ReleaseFeed.update(releases, version("1.1.0")))
    }

    @Test
    fun `a pre-release version is offered both kinds`() {
        val releases = ReleaseFeed.parse(feed(release("v1.2.0-beta.2", preRelease = true), release("v1.1.0")))

        assertEquals("1.2.0-beta.2", ReleaseFeed.update(releases, version("1.2.0-beta.1"))?.version.toString())
        assertEquals("1.2.0-beta.2", ReleaseFeed.update(releases, version("0.9.0"))?.version.toString())
    }

    @Test
    fun `a download is trusted over https, or from the feed's own server`() {
        val github = "https://api.github.com/repos/owner/app/releases"
        val local = "http://10.0.2.2:8000/releases.json"

        assertTrue(ReleaseFeed.trusts(github, "https://github.com/owner/app/releases/download/v1/app.apk"))
        assertFalse(ReleaseFeed.trusts(github, "http://github.com/owner/app/releases/download/v1/app.apk"))
        assertFalse(ReleaseFeed.trusts(github, "file:///sdcard/app.apk"))
        assertFalse(ReleaseFeed.trusts(github, "not a url"))
        assertTrue(ReleaseFeed.trusts(local, "http://10.0.2.2:8000/app.apk"))
        assertFalse(ReleaseFeed.trusts(local, "http://10.0.2.2:9000/app.apk"))
        assertFalse(ReleaseFeed.trusts(local, "http://example.com/app.apk"))
    }
}
