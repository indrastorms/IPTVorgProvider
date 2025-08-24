
package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.io.InputStream
import kotlinx.serialization.Serializable

class IptvorgProvider : MainAPI() {
    override var lang = "en"
    override var mainUrl = "https://raw.githubusercontent.com/iptv-org/iptv/master/README.md"
    override var name = "Iptv-org"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val data = app.get(mainUrl).document
        val table = data.select("tbody")[2].select("td").chunked(3)

        val shows = table.map { nation ->
            val channelUrl = nation[2].text()
            val nationName = nation[0].text()
            val countryCode = channelUrl.substringAfterLast("/")
                .substringBeforeLast(".").lowercase()
            val nationPoster =
                "https://github.com/emcrisostomo/flags/raw/master/png/256/$countryCode.png"

            LiveSearchResponse(
                nationName,
                LoadData(channelUrl, nationName, nationPoster, 0).toJson(),
                this.name,
                TvType.Live,
                nationPoster,
            )
        }

        return HomePageResponse(
            listOf(
                HomePageList(
                    "Nations",
                    shows,
                    true
                )
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val data = IptvPlaylistParser().parseM3U(
            app.get("https://iptv-org.github.io/iptv/index.m3u").text
        )

        return data.items.filter {
            it.title?.lowercase()?.contains(query.lowercase()) ?: false
        }.map { channel ->
            val streamUrl = channel.url ?: return@map null
            val channelName = channel.attributes["tvg-id"] ?: (channel.title ?: "Unknown")
            val posterUrl = channel.attributes["tvg-logo"] ?: ""

            LiveSearchResponse(
                channelName,
                LoadData(streamUrl, channelName, posterUrl, 1).toJson(),
                this@IptvorgProvider.name,
                TvType.Live,
                posterUrl,
            )
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val loadData = parseJson<LoadData>(url)

        return if (loadData.flag == 0) {
            val playlist = IptvPlaylistParser().parseM3U(app.get(loadData.url).text)
            val showlist = playlist.items.mapIndexed { index, channel ->
                val streamUrl = channel.url ?: return@mapIndexed null
                val channelName = channel.title ?: "Unknown"
                val posterUrl = channel.attributes["tvg-logo"] ?: ""

                Episode(
                    LoadData(streamUrl, channelName, posterUrl, 0).toJson(),
                    channelName,
                    null,
                    index + 1,
                    posterUrl
                )
            }.filterNotNull()

            TvSeriesLoadResponse(
                loadData.channelName,
                loadData.url,
                this.name,
                TvType.Live,
                showlist,
                loadData.poster
            )
        } else {
            LiveStreamLoadResponse(
                loadData.channelName,
                loadData.url,
                this.name,
                LoadData(loadData.url, loadData.channelName, loadData.poster, 0).toJson(),
                loadData.poster
            )
        }
    }

    @Serializable
    data class LoadData(
        val url: String,
        val channelName: String,
        val poster: String,
        val flag: Int
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)

        callback.invoke(
            ExtractorLink(
                this@IptvorgProvider.name,
                loadData.channelName,
                loadData.url,
                "",
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        return true
    }
}

// ----------------- Playlist parsing -----------------

data class Playlist(
    val items: List<PlaylistItem> = emptyList(),
)

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()
        if (!reader.readLine().isExtendedM3u()) {
            throw PlaylistParserException.InvalidHeader()
        }
        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var currentIndex = 0
        var line: String? = reader.readLine()
        while (line != null) {
            if (line.isNotEmpty()) {
                if (line.startsWith(EXT_INF)) {
                    val title = line.getTitle()
                    val attributes = line.getAttributes()
                    playlistItems.add(PlaylistItem(title, attributes))
                } else if (line.startsWith(EXT_VLC_OPT)) {
                    val item = playlistItems[currentIndex]
                    val userAgent = line.getTagValue("http-user-agent")
                    val referrer = line.getTagValue("http-referrer")
                    val headers = if (referrer != null) {
                        item.headers + mapOf("referrer" to referrer)
                    } else item.headers
                    playlistItems[currentIndex] =
                        item.copy(userAgent = userAgent, headers = headers)
                } else {
                    if (!line.startsWith("#")) {
                        val item = playlistItems[currentIndex]
                        val url = line.getUrl()
                        val userAgent = line.getUrlParameter("user-agent")
                        val referrer = line.getUrlParameter("referer")
                        val urlHeaders = if (referrer != null) {
                            item.headers + mapOf("referrer" to referrer)
                        } else item.headers
                        playlistItems[currentIndex] =
                            item.copy(
                                url = url,
                                headers = item.headers + urlHeaders,
                                userAgent = userAgent
                            )
                        currentIndex++
                    }
                }
            }
            line = reader.readLine()
        }
        return Playlist(playlistItems)
    }

    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    private fun String.isExtendedM3u(): Boolean = startsWith(EXT_M3U)

    private fun String.getTitle(): String? {
        return split(",").lastOrNull()?.replaceQuotesAndTrim()
    }

    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    private fun String.getUrlParameters(): Map<String, String> {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val headersString = replace(urlRegex, "").replaceQuotesAndTrim()
        return headersString.split("&").mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair.first() to pair.last() else null
        }.toMap()
    }

    private fun String.getUrlParameter(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val keyRegex = Regex("$key=(\\w[^&]*)", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()
        return keyRegex.find(paramsString)?.groups?.get(1)?.value
    }

    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val attributesString = replace(extInfRegex, "").replaceQuotesAndTrim().split(",").first()
        return attributesString.split(Regex("\\s")).mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair.first() to pair.last().replaceQuotesAndTrim() else null
        }.toMap()
    }

    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }

    companion object {
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader :
        PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}
