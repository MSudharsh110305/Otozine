package net.otozine.player.online

import android.util.Log
import net.otozine.player.library.Track
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Minimal Subsonic API client, aimed at Navidrome.
 *
 * Subsonic rather than a bespoke protocol because Navidrome speaks it, it is
 * stable, and every other self-hosted music server speaks it too -- so the
 * server can be swapped later without touching the app.
 *
 * Deliberately hand-rolled over HttpURLConnection: the whole surface is four
 * endpoints returning small JSON documents, and adding Retrofit + Moshi to an
 * app that otherwise has no networking would be more dependency than protocol.
 *
 * Auth is Subsonic's salted-token scheme: a per-request random salt and
 * md5(password + salt). The password never crosses the wire, though the token
 * is only as good as the transport -- so use HTTPS, which the setup script sets
 * up with a real certificate.
 */
class SubsonicClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {

    data class Result<T>(val value: T? = null, val error: String? = null) {
        val ok: Boolean get() = error == null && value != null
    }

    /** Verify the server is reachable and the credentials work. */
    fun ping(): Result<String> {
        val response = get("ping") ?: return Result(error = "Could not reach $baseUrl")
        val status = response.optString("status")
        return if (status == "ok") {
            Result(value = response.optString("serverVersion", "connected"))
        } else {
            Result(error = response.optJSONObject("error")?.optString("message")
                ?: "Server rejected the credentials")
        }
    }

    /** Newest additions, as a starting point for browsing. */
    fun recent(size: Int = 100): Result<List<Track>> {
        val response = get("getAlbumList2", "type" to "newest", "size" to "50")
            ?: return Result(error = "Could not reach the server")

        val albums = response.optJSONObject("albumList2")?.optJSONArray("album")
            ?: return Result(value = emptyList())

        val out = ArrayList<Track>()
        for (i in 0 until minOf(albums.length(), 25)) {
            val albumId = albums.getJSONObject(i).optString("id")
            val detail = get("getAlbum", "id" to albumId) ?: continue
            val songs = detail.optJSONObject("album")?.optJSONArray("song") ?: continue
            for (j in 0 until songs.length()) {
                out += songs.getJSONObject(j).toTrack()
                if (out.size >= size) return Result(value = out)
            }
        }
        return Result(value = out)
    }

    fun search(query: String, limit: Int = 60): Result<List<Track>> {
        val response = get(
            "search3",
            "query" to query,
            "songCount" to limit.toString(),
            "albumCount" to "0",
            "artistCount" to "0",
        ) ?: return Result(error = "Could not reach the server")

        val songs = response.optJSONObject("searchResult3")?.optJSONArray("song")
            ?: return Result(value = emptyList())

        val out = ArrayList<Track>(songs.length())
        for (i in 0 until songs.length()) out += songs.getJSONObject(i).toTrack()
        return Result(value = out)
    }

    fun randomSongs(size: Int = 100): Result<List<Track>> {
        val response = get("getRandomSongs", "size" to size.toString())
            ?: return Result(error = "Could not reach the server")
        val songs = response.optJSONObject("randomSongs")?.optJSONArray("song")
            ?: return Result(value = emptyList())
        val out = ArrayList<Track>(songs.length())
        for (i in 0 until songs.length()) out += songs.getJSONObject(i).toTrack()
        return Result(value = out)
    }

    /**
     * Playable URL for a remote track.
     *
     * `format=opus` asks the server to transcode on the fly if the source is
     * something heavier, which keeps mobile data sane; Navidrome passes Opus
     * through untouched when the source is already Opus, so the library tier
     * streams byte-for-byte with no re-encode.
     */
    fun streamUrl(remoteId: String): String =
        url("stream", "id" to remoteId, "format" to "opus", "maxBitRate" to "128")

    fun coverArtUrl(remoteId: String, size: Int = 512): String =
        url("getCoverArt", "id" to remoteId, "size" to size.toString())

    // ------------------------------------------------------------- plumbing

    private fun get(endpoint: String, vararg params: Pair<String, String>): JSONObject? {
        return try {
            val connection = (URL(url(endpoint, *params)).openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "OtoZine/0.1")
                }
            connection.use {
                if (it.responseCode !in 200..299) {
                    Log.w(TAG, "$endpoint -> HTTP ${it.responseCode}")
                    return null
                }
                JSONObject(it.inputStream.bufferedReader().readText())
                    .optJSONObject("subsonic-response")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$endpoint failed: ${e.message}")
            null
        }
    }

    private fun url(endpoint: String, vararg params: Pair<String, String>): String {
        val salt = Random.nextLong().toString(16).takeLast(12)
        val token = md5(password + salt)
        val query = buildString {
            append("u=").append(enc(username))
            append("&t=").append(token)
            append("&s=").append(salt)
            append("&v=1.16.1&c=OtoZine&f=json")
            params.forEach { (k, v) -> append("&").append(k).append("=").append(enc(v)) }
        }
        return "$baseUrl/rest/$endpoint?$query"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Map a Subsonic song to our Track.
     *
     * Remote tracks are analysed on the server side only as far as tags go --
     * there is no tempo or key, so like device tracks they stay out of smart
     * sequencing. The remote id rides in `contentHash` so the stream URL can be
     * rebuilt without a second lookup.
     */
    private fun JSONObject.toTrack(): Track {
        val remoteId = optString("id")
        return Track(
            id = -(REMOTE_ID_BASE + remoteId.hashCode().toLong().and(0xFFFFFF)),
            contentHash = "remote:$remoteId",
            title = optString("title").takeIf { it.isNotBlank() },
            artist = optString("artist").takeIf { it.isNotBlank() },
            composer = null,
            album = optString("album").takeIf { it.isNotBlank() },
            year = optInt("year").takeIf { it in 1900..2100 },
            language = null,
            durationMs = optLong("duration") * 1000,
            opusPath = remoteId,
            artPath = if (optString("coverArt").isNotBlank()) optString("coverArt") else null,
            bpm = null,
            keyCamelot = null,
            energy = 0.5f,
            danceability = 0.5f,
            replayGainDb = 0f,
            introEndMs = 0L,
            outroStartMs = 0L,
            hookStartMs = 0L,
        )
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    companion object {
        private const val TAG = "OtoZineSubsonic"

        /** Keeps remote ids clear of the device-track range. */
        private const val REMOTE_ID_BASE = 1_000_000_000L
    }
}

/** True for tracks that live on a remote server. */
val Track.isRemote: Boolean get() = contentHash.startsWith("remote:")

/** The server-side id, for building stream and cover URLs. */
val Track.remoteId: String? get() = contentHash.removePrefix("remote:").takeIf { isRemote }
