
package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink.Companion.newExtractorLink
import com.lagradost.cloudstream3.utils.LoadResponseHelper.newEpisode
import com.lagradost.cloudstream3.utils.SearchResponseHelper.newLiveSearchResponse

class IptvorgProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/iptv-org/iptv/master/README.md"
    override var name = "IPTVorg"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    override suspend fun getMainPage(): HomePageResponse {
        val items = listOf(
            HomePageList(
                "IPTV",
                listOf(
                    newLiveSearchResponse("World TV", "", this.name) {
                        url = "world"
                        posterUrl = null
                    }
                ),
                true
            )
        )
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val playlist = IptvPlaylistParser.loadPlaylist(query)
        return playlist.items.mapNotNull { channel ->
            val streamUrl = channel.url ?: return@mapNotNull null
            val channelName = channel.attributes["tvg-id"] ?: (channel.title ?: "Unknown")
            val posterUrl = channel.attributes["tvg-logo"]

            newLiveSearchResponse(channelName, streamUrl, this.name) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val playlist = IptvPlaylistParser.loadPlaylist(url)
        val showList = playlist.items.mapIndexedNotNull { index, channel ->
            val streamUrl = channel.url ?: return@mapIndexedNotNull null
            val channelName = channel.title ?: "Unknown"
            val posterUrl = channel.attributes["tvg-logo"]

            newEpisode(LoadData(streamUrl, channelName, posterUrl, 0).toJson()) {
                name = channelName
                episode = index + 1
                this.posterUrl = posterUrl
            }
        }

        return if (showList.isNotEmpty()) {
            newTvSeriesLoadResponse("IPTV Playlist", url, TvType.Live, showList) {
                posterUrl = null
            }
        } else {
            val firstChannel = playlist.items.firstOrNull()
            val streamUrl = firstChannel?.url ?: throw RuntimeException("Empty playlist")
            newLiveStreamLoadResponse("Live Stream", url, streamUrl) {
                posterUrl = firstChannel.attributes["tvg-logo"]
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadData>(data) ?: return false
        callback(
            newExtractorLink(this.name, loadData.channelName, loadData.url, "", Qualities.Unknown.value, true)
        )
        return true
    }

    data class LoadData(
        val url: String,
        val channelName: String,
        val poster: String?,
        val flag: Int
    )
}
