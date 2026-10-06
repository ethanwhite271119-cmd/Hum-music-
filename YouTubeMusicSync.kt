package com.example.hum.sync

/*
 * YouTube Music sync for the Hum music app (Android, Kotlin)
 *
 * NOTE: YouTube Music has no official public API. This uses the official
 * YouTube Data API v3, which covers YouTube playlists and liked videos
 * (that includes most songs you like/save in YouTube Music). It cannot see
 * YT Music-only items such as saved albums or uploaded songs.
 *
 * ---------------------------------------------------------------
 * 1) GOOGLE CLOUD SETUP
 * ---------------------------------------------------------------
 *  - console.cloud.google.com -> create project -> enable "YouTube Data API v3"
 *  - OAuth consent screen: add scope  https://www.googleapis.com/auth/youtube
 *    (sensitive scope: add yourself as a test user until the app is verified)
 *  - Credentials -> Create OAuth client ID -> Android
 *    (package name + SHA-1 of your debug/release keystore)
 *
 * ---------------------------------------------------------------
 * 2) build.gradle.kts (app) dependencies  (check for latest versions)
 * ---------------------------------------------------------------
 *  implementation("com.google.android.gms:play-services-auth:21.2.0")
 *  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
 *  implementation("com.squareup.retrofit2:retrofit:2.11.0")
 *  implementation("com.squareup.retrofit2:converter-gson:2.11.0")
 *  implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *
 * ---------------------------------------------------------------
 * 3) AndroidManifest.xml
 * ---------------------------------------------------------------
 *  <uses-permission android:name="android.permission.INTERNET" />
 *
 * ---------------------------------------------------------------
 * 4) QUOTA (default 10,000 units/day)
 * ---------------------------------------------------------------
 *  list calls = 1 unit, insert = 50 units, search = 100 units.
 *  Pushing many tracks without a stored videoId burns quota fast,
 *  so save the videoId in your local DB after the first match.
 */

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// =====================================================================
// Your app's model + storage (implement LocalLibrary with Room/SQLite)
// =====================================================================

data class HumTrack(
    val title: String,
    val artist: String,
    val videoId: String? = null // YouTube video ID once known
)

interface LocalLibrary {
    suspend fun allTracks(): List<HumTrack>
    suspend fun upsert(tracks: List<HumTrack>)
}

// =====================================================================
// Auth (Google authorization for the YouTube scope)
// =====================================================================

object YouTubeAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"

    /**
     * If result.hasResolution() is true, launch result.pendingIntent
     * (see the Activity example at the bottom). Otherwise
     * result.accessToken is ready. Access tokens last ~1 hour; calling
     * this again returns a fresh one silently after the first consent.
     */
    suspend fun authorize(context: Context): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()
        return Identity.getAuthorizationClient(context).authorize(request).await()
    }
}

// =====================================================================
// YouTube Data API v3 (Retrofit)
// =====================================================================

data class ResourceId(val kind: String? = null, val videoId: String? = null)

data class Snippet(
    val title: String? = null,
    val description: String? = null,
    val channelTitle: String? = null,
    val videoOwnerChannelTitle: String? = null,
    val resourceId: ResourceId? = null
)

data class PlaylistDto(val id: String, val snippet: Snippet? = null)
data class PlaylistListResponse(val items: List<PlaylistDto>?, val nextPageToken: String?)

data class PlaylistItemDto(val id: String?, val snippet: Snippet? = null)
data class PlaylistItemListResponse(val items: List<PlaylistItemDto>?, val nextPageToken: String?)

data class VideoDto(val id: String, val snippet: Snippet? = null)
data class VideoListResponse(val items: List<VideoDto>?, val nextPageToken: String?)

data class SearchId(val videoId: String? = null)
data class SearchItem(val id: SearchId? = null)
data class SearchResponse(val items: List<SearchItem>?)

data class Status(val privacyStatus: String)
data class CreatePlaylistBody(val snippet: Snippet, val status: Status)
data class AddItemSnippet(val playlistId: String, val resourceId: ResourceId)
data class AddItemBody(val snippet: AddItemSnippet)

interface YouTubeApi {
    @GET("playlists")
    suspend fun myPlaylists(
        @Query("part") part: String = "snippet",
        @Query("mine") mine: Boolean = true,
        @Query("maxResults") maxResults: Int = 50,
        @Query("pageToken") pageToken: String? = null
    ): PlaylistListResponse

    @GET("playlistItems")
    suspend fun playlistItems(
        @Query("playlistId") playlistId: String,
        @Query("part") part: String = "snippet",
        @Query("maxResults") maxResults: Int = 50,
        @Query("pageToken") pageToken: String? = null
    ): PlaylistItemListResponse

    @GET("videos")
    suspend fun likedVideos(
        @Query("part") part: String = "snippet",
        @Query("myRating") myRating: String = "like",
        @Query("maxResults") maxResults: Int = 50,
        @Query("pageToken") pageToken: String? = null
    ): VideoListResponse

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("part") part: String = "snippet",
        @Query("type") type: String = "video",
        @Query("videoCategoryId") categoryId: String = "10", // Music
        @Query("maxResults") maxResults: Int = 1
    ): SearchResponse

    @POST("playlists")
    suspend fun createPlaylist(
        @Body body: CreatePlaylistBody,
        @Query("part") part: String = "snippet,status"
    ): PlaylistDto

    @POST("playlistItems")
    suspend fun addItem(
        @Body body: AddItemBody,
        @Query("part") part: String = "snippet"
    ): PlaylistItemDto
}

fun buildYouTubeApi(accessToken: () -> String): YouTubeApi {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer ${accessToken()}")
                .build()
            chain.proceed(request)
        }
        .build()

    return Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/youtube/v3/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(YouTubeApi::class.java)
}

// =====================================================================
// Sync logic
// =====================================================================

class YouTubeMusicSync(
    private val api: YouTubeApi,
    private val local: LocalLibrary
) {
    /** YouTube/YT Music -> Hum: import liked songs. Returns number of new tracks. */
    suspend fun pullLikedSongs(): Int {
        val known = local.allTracks().mapNotNull { it.videoId }.toSet()
        val imported = mutableListOf<HumTrack>()
        var pageToken: String? = null

        do {
            val page = api.likedVideos(pageToken = pageToken)
            page.items.orEmpty()
                .filter { it.id !in known }
                .forEach { video ->
                    imported += HumTrack(
                        title = video.snippet?.title.orEmpty(),
                        artist = video.snippet?.channelTitle.orEmpty().removeSuffix(" - Topic"),
                        videoId = video.id
                    )
                }
            pageToken = page.nextPageToken
        } while (pageToken != null)

        if (imported.isNotEmpty()) local.upsert(imported)
        return imported.size
    }

    /**
     * Hum -> YouTube/YT Music: add local tracks to a playlist (created if missing).
     * Returns number of tracks added. Stops cleanly if daily quota runs out.
     */
    suspend fun pushToPlaylist(playlistName: String = "Hum Music"): Int {
        val playlistId = findOrCreatePlaylist(playlistName)
        val inPlaylist = playlistVideoIds(playlistId).toMutableSet()
        val resolved = mutableListOf<HumTrack>()
        var added = 0

        for (track in local.allTracks()) {
            try {
                val videoId = track.videoId ?: findVideoId(track) ?: continue
                if (track.videoId == null) resolved += track.copy(videoId = videoId)
                if (videoId in inPlaylist) continue

                api.addItem(
                    AddItemBody(
                        AddItemSnippet(
                            playlistId = playlistId,
                            resourceId = ResourceId("youtube#video", videoId)
                        )
                    )
                )
                inPlaylist += videoId
                added++
            } catch (e: HttpException) {
                if (e.code() == 403) break // quotaExceeded: try again tomorrow
                // skip this track on other errors (e.g. 404 video unavailable)
            }
        }

        if (resolved.isNotEmpty()) local.upsert(resolved) // remember IDs, saves quota
        return added
    }

    /** Two-way sync. */
    suspend fun syncAll(): Pair<Int, Int> = pullLikedSongs() to pushToPlaylist()

    // ---------- helpers ----------

    private suspend fun findOrCreatePlaylist(name: String): String {
        var pageToken: String? = null
        do {
            val page = api.myPlaylists(pageToken = pageToken)
            page.items.orEmpty()
                .firstOrNull { it.snippet?.title == name }
                ?.let { return it.id }
            pageToken = page.nextPageToken
        } while (pageToken != null)

        return api.createPlaylist(
            CreatePlaylistBody(
                snippet = Snippet(
                    title = name,
                    description = "Synced from the Hum music app"
                ),
                status = Status("private")
            )
        ).id
    }

    private suspend fun playlistVideoIds(playlistId: String): Set<String> {
        val ids = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val page = api.playlistItems(playlistId = playlistId, pageToken = pageToken)
            page.items.orEmpty()
                .mapNotNullTo(ids) { it.snippet?.resourceId?.videoId }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return ids
    }

    private suspend fun findVideoId(track: HumTrack): String? =
        api.search("${track.artist} ${track.title}")
            .items?.firstOrNull()?.id?.videoId
}

// =====================================================================
// Example usage in an Activity
// =====================================================================
/*
class SyncActivity : ComponentActivity() {

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val result = Identity.getAuthorizationClient(this)
            .getAuthorizationResultFromIntent(res.data)
        result.accessToken?.let { runSync(it) }
    }

    fun onSyncClicked() {
        lifecycleScope.launch {
            val result = YouTubeAuth.authorize(this@SyncActivity)
            if (result.hasResolution()) {
                // First time: show Google's consent screen
                consentLauncher.launch(
                    IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()
                )
            } else {
                runSync(result.accessToken!!)
            }
        }
    }

    private fun runSync(token: String) = lifecycleScope.launch(Dispatchers.IO) {
        val api = buildYouTubeApi { token }
        val sync = YouTubeMusicSync(api, MyRoomLibrary(db)) // your LocalLibrary impl
        val (imported, exported) = sync.syncAll()
        withContext(Dispatchers.Main) {
            Toast.makeText(
                this@SyncActivity,
                "Imported $imported, added $exported to YouTube",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
*/
