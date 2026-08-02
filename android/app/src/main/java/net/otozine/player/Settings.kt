package net.otozine.player

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Where the audio actually lives once a library is imported. */
enum class StorageMode {
    /**
     * Play straight off the drive. Nothing is copied, so the library takes no
     * phone storage -- but the drive has to be plugged in to hear anything.
     */
    LINK,

    /**
     * Copy the audio into the app. Uses phone storage and survives unplugging.
     */
    COPY,
}

/** Where playable audio comes from. */
enum class Source {
    /** The curated, analysed library produced by the Librarian. */
    LIBRARY,

    /** Whatever audio is already on the phone. No analysis, no normalisation. */
    DEVICE,

    /** A remote Navidrome/Subsonic server. */
    ONLINE,
}

@Immutable
data class Prefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Name of an OtoPalette, or null to follow [theme]. */
    val palette: String? = null,
    val includeDeviceAudio: Boolean = false,
    val serverUrl: String = "",
    val serverUser: String = "",
    val serverPassword: String = "",
    val streamWhenAvailable: Boolean = false,
    /** False until the app has had one chance to set itself up. */
    val firstRunDone: Boolean = false,
    /**
     * Require a double tap to jump within a track.
     *
     * Single tap is quicker but easy to trigger by accident while the phone is
     * in a pocket or being handed over, and an accidental seek loses your place.
     */
    val seekOnDoubleTap: Boolean = false,
    val storageMode: StorageMode = StorageMode.LINK,
    /** SAF tree the library was imported from, so it can be found again. */
    val libraryTreeUri: String = "",
) {
    val serverConfigured: Boolean
        get() = serverUrl.isNotBlank() && serverUser.isNotBlank()
}

/**
 * Persisted preferences.
 *
 * SharedPreferences rather than DataStore: this is a handful of scalars read
 * once at startup, and DataStore would add a dependency and a coroutine surface
 * for no benefit at this size.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("otozine.prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Prefs> = _state

    private fun load() = Prefs(
        theme = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM),
        palette = prefs.getString(KEY_PALETTE, null),
        includeDeviceAudio = prefs.getBoolean(KEY_DEVICE_AUDIO, false),
        serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
        serverUser = prefs.getString(KEY_SERVER_USER, "").orEmpty(),
        serverPassword = prefs.getString(KEY_SERVER_PASS, "").orEmpty(),
        streamWhenAvailable = prefs.getBoolean(KEY_STREAM, false),
        firstRunDone = prefs.getBoolean(KEY_FIRST_RUN, false),
        seekOnDoubleTap = prefs.getBoolean(KEY_DOUBLE_TAP_SEEK, false),
        storageMode = runCatching {
            StorageMode.valueOf(prefs.getString(KEY_STORAGE_MODE, null) ?: StorageMode.LINK.name)
        }.getOrDefault(StorageMode.LINK),
        libraryTreeUri = prefs.getString(KEY_TREE_URI, "").orEmpty(),
    )

    fun setStorageMode(mode: StorageMode) {
        prefs.edit().putString(KEY_STORAGE_MODE, mode.name).apply()
        _state.value = _state.value.copy(storageMode = mode)
    }

    fun setLibraryTreeUri(uri: String) {
        prefs.edit().putString(KEY_TREE_URI, uri).apply()
        _state.value = _state.value.copy(libraryTreeUri = uri)
    }

    fun setSeekOnDoubleTap(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOUBLE_TAP_SEEK, enabled).apply()
        _state.value = _state.value.copy(seekOnDoubleTap = enabled)
    }

    fun markFirstRunDone() {
        prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply()
        _state.value = _state.value.copy(firstRunDone = true)
    }

    /** @param name an OtoPalette name, or null to go back to light/dark. */
    fun setPalette(name: String?) {
        prefs.edit().putString(KEY_PALETTE, name).apply()
        _state.value = _state.value.copy(palette = name)
    }

    fun setTheme(mode: ThemeMode) {
        // Choosing plain light/dark drops any named palette, so the two
        // controls cannot disagree about what should be on screen.
        prefs.edit().putString(KEY_THEME, mode.name).putString(KEY_PALETTE, null).apply()
        _state.value = _state.value.copy(theme = mode, palette = null)
    }

    fun setIncludeDeviceAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVICE_AUDIO, enabled).apply()
        _state.value = _state.value.copy(includeDeviceAudio = enabled)
    }

    fun setServer(url: String, user: String, password: String) {
        // Normalise once here so every caller can assume no trailing slash.
        val clean = url.trim().trimEnd('/')
        prefs.edit()
            .putString(KEY_SERVER_URL, clean)
            .putString(KEY_SERVER_USER, user.trim())
            .putString(KEY_SERVER_PASS, password)
            .apply()
        _state.value = _state.value.copy(
            serverUrl = clean, serverUser = user.trim(), serverPassword = password,
        )
    }

    fun setStreamWhenAvailable(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STREAM, enabled).apply()
        _state.value = _state.value.copy(streamWhenAvailable = enabled)
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_PALETTE = "palette"
        const val KEY_DEVICE_AUDIO = "device_audio"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SERVER_USER = "server_user"
        const val KEY_SERVER_PASS = "server_pass"
        const val KEY_STREAM = "stream_when_available"
        const val KEY_FIRST_RUN = "first_run_done"
        const val KEY_DOUBLE_TAP_SEEK = "seek_double_tap"
        const val KEY_STORAGE_MODE = "storage_mode"
        const val KEY_TREE_URI = "library_tree_uri"
    }
}
