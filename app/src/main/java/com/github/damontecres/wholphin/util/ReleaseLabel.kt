package com.github.damontecres.wholphin.util

private fun token(pattern: String) = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])", RegexOption.IGNORE_CASE)

private val RESOLUTION = token("(2160|1080|720|576|480)[pi]")
private val FOUR_K = token("4k|uhd")

// A bare "web" is often part of a title, so it only counts next to the resolution
private val BARE_WEB = token("(?:2160|1080|720)p[ ._-]+web|web[ ._-]+(?:2160|1080|720)p")

/** The most specific first: a remux is usually also named BluRay */
private val RELEASE_TYPES =
    listOf(
        token("remux") to "Remux",
        token("web[ ._-]?dl") to "WEB-DL",
        token("web[ ._-]?rip") to "WEBRip",
        token("bd[ ._-]?rip|br[ ._-]?rip") to "BDRip",
        token("blu[ ._-]?ray") to "BluRay",
        token("hd[ ._-]?light|4k[ ._-]?light") to "HDLight",
        token("hdtv") to "HDTV",
        token("dvd[ ._-]?rip") to "DVDRip",
        BARE_WEB to "WEB",
    )

/**
 * The release type and resolution found in a file name or path, such as "Remux 2160p" or "WEB-DL 1080p".
 * Null when the name does not tell the release type.
 */
fun releaseLabel(path: String?): String? {
    val fileName = path?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    val type = RELEASE_TYPES.firstOrNull { (regex, _) -> regex.containsMatchIn(fileName) }?.second ?: return null
    val resolution =
        RESOLUTION.find(fileName)?.value?.lowercase()
            ?: "2160p".takeIf { FOUR_K.containsMatchIn(fileName) }
    return listOfNotNull(type, resolution).joinToString(" ")
}
