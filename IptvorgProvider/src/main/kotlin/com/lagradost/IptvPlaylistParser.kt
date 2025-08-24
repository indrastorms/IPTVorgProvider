
package com.lagradost

object IptvPlaylistParser {
    data class Channel(
        val title: String?,
        val url: String?,
        val attributes: Map<String, String>
    )

    data class Playlist(val items: List<Channel>)

    fun loadPlaylist(content: String): Playlist {
        val lines = content.lines()
        val channels = mutableListOf<Channel>()
        var currentAttrs = mutableMapOf<String, String>()
        var currentTitle: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF") -> {
                    currentAttrs = parseAttributes(line).toMutableMap()
                    currentTitle = line.substringAfter(",").trim()
                }
                line.startsWith("#") -> continue
                line.isNotBlank() -> {
                    channels.add(Channel(currentTitle, line.trim(), currentAttrs))
                    currentAttrs = mutableMapOf()
                    currentTitle = null
                }
            }
        }
        return Playlist(channels)
    }

    private fun parseAttributes(line: String): Map<String, String> {
        val regex = """(\w+)="([^"]*)"""".toRegex()
        return regex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
