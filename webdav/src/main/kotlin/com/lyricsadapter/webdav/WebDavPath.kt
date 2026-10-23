package com.lyricsadapter.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Library paths are relative to the configured server URL and start with
 * `/`, as desktop's `webdavPath.ts` keeps them: `https://host/dav` plus
 * `/Music/a.flac` is `https://host/dav/Music/a.flac`. A path that already
 * carries the base's segments is not doubled.
 */
object WebDavPath {
    /** [directory] adds the trailing slash collections are addressed with, saving servers a redirect. */
    fun url(serverUrl: String, path: String, directory: Boolean = false): HttpUrl {
        val base = serverUrl.toHttpUrl()
        val baseSegments = base.segments()
        val input = path.split('/').filter(String::isNotEmpty)
        val relative = if (baseSegments.isNotEmpty() && input.take(baseSegments.size) == baseSegments) input.drop(baseSegments.size) else input
        return base.newBuilder().encodedPath("/").apply {
            (baseSegments + relative).forEach { addPathSegment(it) }
            if (directory && (baseSegments + relative).isNotEmpty()) addPathSegment("")
        }.build()
    }

    /** The library path of a PROPFIND `href`, which may be absolute or server-relative. */
    fun fromHref(href: String, serverUrl: String): String {
        val base = serverUrl.toHttpUrl()
        val target = base.resolve(href) ?: return href
        val baseSegments = base.segments()
        val segments = target.segments()
        val relative = if (baseSegments.isNotEmpty() && segments.take(baseSegments.size) == baseSegments) segments.drop(baseSegments.size) else segments
        return "/" + relative.joinToString("/")
    }

    /** Decoded segments, without the empty one a trailing slash leaves. */
    private fun HttpUrl.segments(): List<String> = pathSegments.filter(String::isNotEmpty)
}
