package net.otozine.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.otozine.player.analysis.AnalysisWorker
import net.otozine.player.library.DriveTransfer
import net.otozine.player.library.TransferService
import net.otozine.player.library.DeviceAudio
import net.otozine.player.library.DriveExporter
import net.otozine.player.library.DriveWatcher
import net.otozine.player.library.LibraryImporter
import net.otozine.player.library.PlayHistory
import net.otozine.player.library.Track
import net.otozine.player.library.isAnalysed
import net.otozine.player.library.isDevice
import net.otozine.player.online.SubsonicClient
import net.otozine.player.online.isRemote
import net.otozine.player.online.remoteId
import net.otozine.player.queue.Features
import net.otozine.player.queue.SkipModel
import android.util.Log
import net.otozine.player.queue.QueueEngine
import java.util.UUID

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        /** Analysed tracks from the Librarian. The only ones smart queues use. */
        val libraryTracks: List<Track> = emptyList(),
        /** Audio already on the phone. Playable, unanalysed. */
        val deviceTracks: List<Track> = emptyList(),
        /** Tracks on the streaming server. */
        val remoteTracks: List<Track> = emptyList(),

        val loading: Boolean = true,
        val libraryPresent: Boolean = false,
        val nowPlayingId: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val output: String = "OUTPUT",
        val queue: List<QueueEngine.Entry> = emptyList(),
        val adventure: Float = 0.35f,
        val mood: QueueEngine.Mood? = null,
        /** Mood words the queue is currently steering toward. */
        val targetMoods: Set<String> = emptySet(),
        val languageFilter: Set<String> = emptySet(),
        val searchQuery: String = "",
        val historyEvents: Int = 0,
        /**
         * Ids that have ever been played, for the "Never played" shelf.
         *
         * The count alone cannot answer "have I heard this one", which is why
         * the shelf used to be ten random tracks wearing the wrong label.
         */
        val playedTrackIds: Set<Long> = emptySet(),
        /** Progress while copying phone music onto the drive. */
        val transferring: DriveTransfer.Progress? = null,
        /** Minimised to a strip so the app stays usable while it runs. */
        val transferMinimised: Boolean = false,
        val analysisMinimised: Boolean = false,
        val queueMode: QueueMode = QueueMode.ANTI_REPEAT,
        val pendingSync: Int = 0,
        val sleepTimerEndsAt: Long? = null,
        val importing: LibraryImporter.Progress? = null,
        val serverStatus: String? = null,
        val serverBusy: Boolean = false,
        /** Mood labels you gave the current track. */
        val currentMoods: Set<String> = emptySet(),
        /** What the analyser heard, for comparison. */
        val currentGuessedMoods: List<String> = emptyList(),
        val labelledCount: Int = 0,
        /** Mood labels per track: yours where given, the analyser's otherwise. */
        val moodsByTrack: Map<Long, List<String>> = emptyMap(),
        val driveState: DriveWatcher.State = DriveWatcher.State.NONE,
        val busyMessage: String? = null,
        val analysing: AnalysisWorker.Progress? = null,
    ) {
        /** Everything playable, whatever its source. */
        val tracks: List<Track> get() = libraryTracks + deviceTracks + remoteTracks
        /**
         * Distinct songs, not rows.
         *
         * `tracks` concatenates the drive, the phone and the server, so a song
         * copied from the phone to the drive appeared in two of them and the
         * count climbed every time music was copied without a single new song
         * existing. Matching on title and length is the same test the transfer
         * uses to skip duplicates.
         */
        val playableCount: Int get() {
            val seen = HashSet<String>()
            var n = 0
            for (track in tracks) if (seen.add(track.dedupeKey)) n++
            return n
        }

        val nowPlaying: Track? get() = tracks.firstOrNull { it.id.toString() == nowPlayingId }

        val currentReason: QueueEngine.Entry?
            get() = queue.firstOrNull { it.track.id.toString() == nowPlayingId }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val settings = Settings(app)
    val prefs: StateFlow<Prefs> = settings.state

    private val library get() = getApplication<OtoZineApp>().library
    private val history = PlayHistory(app)
    private val importer = LibraryImporter(app)

    private var controller: MediaController? = null
    private var sessionId = UUID.randomUUID().toString()

    private var openTrackId: Long? = null
    private var openStartedAt: Long = 0
    private var openStartPosition: Long = 0
    private var previousTrackId: Long? = null
    private var deviceMoods: Map<Long, Set<String>> = emptyMap()

    /**
     * Refitted on load rather than updated per event.
     *
     * Training over a few thousand rows takes single-digit milliseconds, so
     * there is nothing to gain from incremental updates and a full refit cannot
     * drift out of step with the history it claims to describe.
     */
    private val skipModel = SkipModel()
    private var analysisJob: kotlinx.coroutines.Job? = null
    private var transferJob: kotlinx.coroutines.Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            closeOpenEvent()
            _state.value = _state.value.copy(nowPlayingId = mediaItem?.mediaId)
            mediaItem?.mediaId?.toLongOrNull()?.let {
                openEvent(it)
                refreshMoodsFor(it)
            }
            seekPastIntro(mediaItem)
            rememberSession()
        }
    }

    init {
        connect()
        loadLibrary()
        trackPosition()
    }

    // ------------------------------------------------------------- settings

    fun setSeekOnDoubleTap(enabled: Boolean) = settings.setSeekOnDoubleTap(enabled)

    /**
     * Switch where imported audio lives, and move it now.
     *
     * This used to set a flag that only took effect on the next import, while
     * the text beside it described the new mode in the present tense -- so
     * choosing "copy to phone" looked like it had done something and had not.
     * Now the setting and the files agree the moment you pick.
     */
    private var adventureJob: kotlinx.coroutines.Job? = null

    /**
     * Rebuild while the dial is still moving, without rebuilding per pixel.
     *
     * A drag emits a change every frame and a rebuild scores the whole library,
     * so doing it eagerly would stutter. 180 ms is under the threshold where a
     * response stops feeling immediate, and long enough that a full swipe costs
     * one rebuild rather than sixty.
     */
    /**
     * Write down what is playing, for the next launch.
     *
     * Called on every track change rather than on a timer: it is a handful of
     * ids into SharedPreferences, and a timer would be the one thing still
     * running when the app is killed -- exactly when the record matters most.
     */
    private fun rememberSession() {
        val queue = _state.value.queue.map { it.track.id }
        if (queue.isEmpty()) return
        settings.saveSession(
            trackIds = queue,
            currentId = _state.value.nowPlayingId?.toLongOrNull(),
            positionMs = controller?.currentPosition ?: 0L,
        )
    }

    /**
     * Put the last session back, paused.
     *
     * Paused deliberately. Opening a music app should not start making noise on
     * its own -- the queue and the position are restored so one tap continues
     * where you left off, and doing nothing stays silent.
     *
     * Tracks that have since disappeared are dropped rather than treated as an
     * error: a pendrive that is not plugged in today is the normal case, not a
     * corrupt session.
     */
    private fun restoreSession() {
        if (_state.value.queue.isNotEmpty()) return          // already playing
        val saved = settings.savedSession() ?: return
        val byId = _state.value.tracks.associateBy { it.id }

        val tracks = saved.trackIds.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val items = tracks.mapNotNull { mediaItemFor(it) }
        if (items.isEmpty()) return

        val index = tracks.indexOfFirst { it.id == saved.currentId }.coerceAtLeast(0)
        _state.value = _state.value.copy(
            queue = tracks.map {
                QueueEngine.Entry(it, listOf(QueueEngine.Reason("carried over", 1f)), 1f)
            },
            nowPlayingId = tracks[index].id.toString(),
        )
        controller?.apply {
            setMediaItems(items, index, saved.positionMs)
            prepare()
        }
    }

    fun rebuildTailDebounced() {
        adventureJob?.cancel()
        adventureJob = viewModelScope.launch {
            delay(180)
            rebuildTail()
        }
    }

    fun setPalette(name: String?) = settings.setPalette(name)

    fun setStorageMode(mode: StorageMode) {
        if (settings.state.value.storageMode == mode) return
        settings.setStorageMode(mode)
        if (_state.value.transferring?.finished == false) return

        transferJob = viewModelScope.launch {
            val work = withContext(Dispatchers.IO) {
                _state.value.libraryTracks.filter { track ->
                    val onDrive = track.opusPath?.startsWith("content://") == true
                    if (mode == StorageMode.COPY) onDrive else !onDrive
                }
            }
            if (work.isEmpty()) return@launch

            val verb = if (mode == StorageMode.COPY) "copying to phone" else "freeing space"
            var done = 0
            var moved = 0
            for (track in work) {
                _state.value = _state.value.copy(
                    transferring = DriveTransfer.Progress(
                        kind = DriveTransfer.Kind.ON_PHONE,
                        done = done, total = work.size,
                        currentTitle = track.displayTitle, stage = verb,
                    ),
                )
                TransferService.update(
                    getApplication(), "${track.displayTitle} — $verb", done, work.size,
                )
                val ok = withContext(Dispatchers.IO) {
                    if (mode == StorageMode.COPY) library.copyLocal(track)
                    else library.freeLocal(track)
                }
                if (ok) moved++
                done++
            }

            TransferService.stop(getApplication())
            _state.value = _state.value.copy(
                transferring = DriveTransfer.Progress(
                    kind = DriveTransfer.Kind.ON_PHONE,
                    done = done, total = work.size, finished = true,
                    stage = verb, copied = moved, failed = done - moved,
                ),
                transferMinimised = false,
            )
            refresh()
            delay(3000)
            _state.value = _state.value.copy(transferring = null)
        }
    }

    /**
     * Re-check whether the drive is reachable.
     *
     * Called on resume as well as at startup, because the usual way this
     * changes is the user unplugging the drive while the app is open -- and
     * there is no broadcast for that.
     */
    fun refreshDriveState() {
        viewModelScope.launch(Dispatchers.IO) {
            val probe = _state.value.libraryTracks
                .firstOrNull { it.opusPath?.startsWith("content://") == true }?.opusPath
            val driveState = DriveWatcher.check(
                getApplication(), settings.state.value.libraryTreeUri, probe,
            )
            _state.value = _state.value.copy(driveState = driveState)
            if (driveState == DriveWatcher.State.CONNECTED) syncHistoryToDrive()
        }
    }

    /** Remove a track from the library index, and its file when we own it. */
    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyMessage = "Removing…")
            val removed = withContext(Dispatchers.IO) { library.delete(track) }
            _state.value = _state.value.copy(busyMessage = null)
            if (removed) refresh()
        }
    }

    /**
     * Copy a track from the drive onto the phone so it survives unplugging.
     *
     * The counterpart to LINK mode: rather than choosing between "all on the
     * drive" and "all on the phone", individual tracks can be pinned.
     */
    fun copyToPhone(track: Track) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyMessage = "Copying to phone…")
            val ok = withContext(Dispatchers.IO) { library.copyLocal(track) }
            _state.value = _state.value.copy(
                busyMessage = if (ok) null else "Could not copy — is the drive connected?"
            )
            if (ok) refresh()
        }
    }

    /**
     * Put a phone track into the drive's inbox for the Librarian to process.
     *
     * Copying only. The analysis that produces the Opus tier and the loudness
     * levelling happens on a PC; this just gets the file to where that will
     * find it.
     */
    fun sendToDrive(track: Track) {
        viewModelScope.launch {
            val uri = track.opusPath
            if (uri == null || !uri.startsWith("content://")) {
                _state.value = _state.value.copy(
                    busyMessage = "Only tracks on this phone can be sent to the drive."
                )
                return@launch
            }
            _state.value = _state.value.copy(busyMessage = "Copying to drive…")

            val safeName = track.displayTitle
                .replace(Regex("""[\/:*?"<>|]"""), "_")
                .take(80)
                .ifBlank { "track" } + ".mp3"

            val result = withContext(Dispatchers.IO) {
                DriveExporter.send(
                    getApplication(), settings.state.value.libraryTreeUri,
                    Uri.parse(uri), safeName,
                )
            }
            _state.value = _state.value.copy(
                busyMessage = when (result) {
                    is DriveExporter.Result.Ok ->
                        "Copied to the drive inbox. Run ingest on a PC to analyse it."
                    is DriveExporter.Result.Failed -> result.reason
                }
            )
        }
    }

    /**
     * Measure loudness, tempo, key and mood for anything not yet analysed.
     *
     * Runs entirely on the phone, so a PC is only needed for the parts it
     * genuinely cannot do -- fingerprinting, online metadata, and building the
     * Opus tier. Everything that governs how the app behaves is measurable here.
     */
    fun analyseLibrary() {
        if (_state.value.analysing?.finished == false) return   // already running

        analysisJob = viewModelScope.launch {
            val worker = AnalysisWorker(getApplication(), history)
            val candidates = _state.value.libraryTracks + _state.value.deviceTracks

            val result = worker.analyseMissing(
                tracks = candidates,
                resolve = { track ->
                    track.opusPath?.let { path ->
                        if (path.startsWith("content://")) Uri.parse(path)
                        else library.audioUri(track)
                    }
                },
                onProgress = { progress ->
                    _state.value = _state.value.copy(analysing = progress)
                },
            )
            _state.value = _state.value.copy(analysing = result, analysisMinimised = false)
            refresh()
            delay(2500)
            _state.value = _state.value.copy(analysing = null)
        }
    }

    /**
     * Copy phone music onto the drive as a proper library entry.
     *
     * Archives the original, encodes the phone tier, measures the track and
     * writes the row -- the same end state the Librarian would produce, without
     * a PC. The audio and the record both live on the drive, so unplugging it
     * takes them with it.
     */
    fun copyToDrive(tracks: List<Track>) {
        if (_state.value.transferring?.finished == false) return   // already running
        val candidates = tracks.filter { it.isDevice }
        if (candidates.isEmpty()) return

        transferJob = viewModelScope.launch {
            // Reload the drive list the moment a song lands, not when the batch
            // ends. Copying two hundred songs takes hours, and a library that
            // only updates at the end looks like it is doing nothing for all of
            // it. Reading a few hundred rows costs microseconds next to the
            // minute of encoding that preceded it.
            var seen = 0
            val result = DriveTransfer.copyToDrive(
                context = getApplication(),
                treeUri = settings.state.value.libraryTreeUri,
                tracks = candidates,
                localDb = java.io.File(library.root, "library.db"),
                resolve = { track -> track.opusPath?.let { Uri.parse(it) } },
                onProgress = { progress ->
                    _state.value = _state.value.copy(transferring = progress)
                    if (!progress.finished) {
                        TransferService.update(
                            getApplication(),
                            "${progress.currentTitle} — ${progress.stage}",
                            progress.done, progress.total,
                        )
                    }
                    if (progress.copied > seen) {
                        seen = progress.copied
                        viewModelScope.launch(Dispatchers.IO) {
                            // Drop the cached handle first: it was opened before
                            // these rows existed, and reusing it would keep
                            // returning the library as it was when the copy began.
                            library.close()
                            val fresh = library.tracks()
                            val tags = library.moodTags()
                            withContext(Dispatchers.Main) {
                                moodTags = tags
                                _state.value = _state.value.copy(
                                    libraryTracks = fresh,
                                    libraryPresent = library.isPresent,
                                    moodsByTrack = moodMap().mapValues { it.value.toList() },
                                )
                            }
                        }
                    }
                },
            )
            TransferService.stop(getApplication())
            _state.value = _state.value.copy(transferring = result, transferMinimised = false)
            refresh()
            delay(3000)
            _state.value = _state.value.copy(transferring = null)
        }
    }

    /**
     * Push this phone's play history onto the drive.
     *
     * Runs whenever the drive turns up, because that is the only moment it can:
     * the outbox has been filling since the last time and there is no other
     * chance to empty it. Events are marked synced only after the write lands,
     * so a drive pulled mid-write means a retry, not a hole in the history.
     */
    fun syncHistoryToDrive() {
        viewModelScope.launch(Dispatchers.IO) {
            val treeUri = settings.state.value.libraryTreeUri
            if (treeUri.isBlank()) return@launch
            val pending = history.unsynced(history.deviceId)
            if (pending.ids.isEmpty()) return@launch

            val ok = DriveTransfer.syncEvents(
                getApplication(), treeUri, history.deviceId, pending.jsonl,
            )
            if (ok) {
                history.markSynced(pending.ids)
                _state.value = _state.value.copy(pendingSync = history.pendingSyncCount())
            }
        }
    }

    fun minimiseAnalysis(minimised: Boolean) {
        _state.value = _state.value.copy(analysisMinimised = minimised)
    }

    fun minimiseTransfer(minimised: Boolean) {
        _state.value = _state.value.copy(transferMinimised = minimised)
    }

    fun cancelTransfer() {
        TransferService.stop(getApplication())
        transferJob?.cancel()
        transferJob = null
        _state.value = _state.value.copy(transferring = null)
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _state.value = _state.value.copy(analysing = null)
    }

    fun dismissBusy() {
        _state.value = _state.value.copy(busyMessage = null)
    }

    fun setIncludeDeviceAudio(enabled: Boolean) {
        settings.setIncludeDeviceAudio(enabled)
        if (enabled) scanDevice() else _state.value = _state.value.copy(deviceTracks = emptyList())
    }

    /**
     * Called once permission to read audio exists.
     *
     * On a first run this switches device audio on by itself. Opening a music
     * player and being told there is no music -- when the phone is full of it --
     * is a bad first impression, and the fix costs nothing: the scan is a single
     * MediaStore query.
     */
    fun onAudioPermissionGranted() {
        val p = settings.state.value
        if (!p.firstRunDone) {
            settings.markFirstRunDone()
            settings.setIncludeDeviceAudio(true)
            scanDevice()
        } else if (p.includeDeviceAudio) {
            scanDevice()
        }
    }

    fun saveServer(url: String, user: String, password: String) {
        settings.setServer(url, user, password)
        testServer()
    }

    // --------------------------------------------------------------- wiring

    private fun connect() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), net.otozine.player.playback.PlaybackService::class.java),
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
            _state.value = _state.value.copy(
                isPlaying = controller?.isPlaying ?: false,
                nowPlayingId = controller?.currentMediaItem?.mediaId,
            )
            if (controller?.currentMediaItem == null) restoreSession()
        }, MoreExecutors.directExecutor())
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) { library.tracks() }
            val snapshot = withContext(Dispatchers.IO) { history.snapshot() }
            val pending = withContext(Dispatchers.IO) { history.pendingSyncCount() }
            moodTags = withContext(Dispatchers.IO) { library.moodTags() }
            userMoods = withContext(Dispatchers.IO) { history.allMoods() }
            _state.value = _state.value.copy(
                libraryTracks = tracks,
                moodsByTrack = moodMap().mapValues { it.value.toList() },
                labelledCount = withContext(Dispatchers.IO) { history.labelledCount() },
                loading = false,
                libraryPresent = library.isPresent,
                queueMode = settings.state.value.queueMode,
                historyEvents = snapshot.totalEvents,
                playedTrackIds = snapshot.lastPlayedAt.keys,
                pendingSync = pending,
            )
            trainSkipModel(tracks)
            restoreSession()
            if (settings.state.value.includeDeviceAudio) scanDevice()
            if (settings.state.value.serverConfigured) loadRemote()
            refreshDriveState()
        }
    }

    /** Fit the skip model from recorded listens, and remember the result. */
    private suspend fun trainSkipModel(library: List<Track>) {
        withContext(Dispatchers.IO) {
            val byId = library.associateBy { it.id }
            val used = skipModel.train(history.outcomes(), byId::get)
            if (skipModel.trained) {
                history.saveWeights(SkipModel.KEY, skipModel.exportWeights())
                Log.i("OtoZineQueue", "skip model fitted from $used listens")
            } else {
                // Not enough history yet. Fall back to whatever was learned on a
                // previous run rather than starting blind every launch.
                history.loadWeights(SkipModel.KEY, SkipModel.FEATURES)
                    ?.let { skipModel.importWeights(it) }
            }
        }
    }

    private fun scanDevice() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { DeviceAudio.scan(getApplication()) }
            // Fold in anything the phone has already measured. MediaStore knows
            // nothing about tempo or mood, so without this a track would come
            // back unanalysed after every scan and be queued for measuring
            // again on every run.
            val measured = withContext(Dispatchers.IO) { history.deviceAnalysis() }
            deviceMoods = measured
                .filterValues { it.moods.isNotEmpty() }
                .mapValues { it.value.moods.toSet() }

            val enriched = found.map { track ->
                val a = measured[track.id] ?: return@map track
                track.copy(
                    bpm = a.bpm,
                    keyCamelot = a.keyCamelot,
                    energy = a.energy,
                    danceability = a.danceability,
                    replayGainDb = a.replayGainDb,
                    durationMs = if (track.durationMs > 0) track.durationMs else a.durationMs,
                )
            }
            _state.value = _state.value.copy(
                deviceTracks = enriched,
                moodsByTrack = moodMap().mapValues { it.value.toList() },
            )
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        loadLibrary()
    }

    /**
     * Where a track's artwork lives.
     *
     * The curated library has artwork files beside the audio. Device tracks do
     * not, so they resolve to the audio file itself and the cache reads the
     * picture embedded in it -- without that the app drew a generated tile for a
     * song whose notification was showing a real cover. Remote art still needs a
     * network fetch we do not do, and falls back to the generated tile, which is
     * why that fallback had to be a designed state rather than a placeholder.
     */
    fun artPathFor(track: Track): String? = when {
        track.isRemote -> null
        track.isDevice -> track.opusPath
        else -> library.artFile(track)?.path
    }

    fun importFrom(treeUri: Uri) {
        viewModelScope.launch {
            library.close()
            settings.setLibraryTreeUri(treeUri.toString())
            val linkOnly = settings.state.value.storageMode == StorageMode.LINK
            val result = importer.import(treeUri, linkOnly) { progress ->
                _state.value = _state.value.copy(importing = progress)
            }
            _state.value = _state.value.copy(importing = result)
            if (result.done) {
                refresh()
                delay(1200)
                _state.value = _state.value.copy(importing = null)
            }
        }
    }

    fun dismissImport() {
        _state.value = _state.value.copy(importing = null)
    }

    private fun trackPosition() {
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    // Read the position whether or not it is playing. Gating on
                    // isPlaying meant seeking while paused moved the player but
                    // never moved the bar, so the scrub appeared to do nothing.
                    val position = c.currentPosition
                    if (position != _state.value.positionMs) {
                        _state.value = _state.value.copy(positionMs = position)
                    }
                }
                checkSleepTimer()
                delay(400)
            }
        }
    }

    // ----------------------------------------------------------- mood labels

    private var moodTags: Map<Long, Set<String>> = emptyMap()
    private var userMoods: Map<Long, Set<String>> = emptyMap()

    /** Save your labels for a track and rebuild the tail of the queue. */
    fun saveMoods(trackId: Long, moods: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            history.setMoods(trackId, moods)
            userMoods = history.allMoods()
            _state.value = _state.value.copy(
                currentMoods = moods,
                moodsByTrack = moodMap().mapValues { it.value.toList() },
                labelledCount = history.labelledCount(),
            )
        }
    }

    private fun refreshMoodsFor(trackId: Long?) {
        if (trackId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val mine = history.moodsFor(trackId)
            _state.value = _state.value.copy(
                currentMoods = mine,
                currentGuessedMoods = moodTags[trackId].orEmpty().toList().take(4),
            )
        }
    }

    // ---------------------------------------------------------------- online

    private fun client(): SubsonicClient? {
        val p = settings.state.value
        if (!p.serverConfigured) return null
        return SubsonicClient(p.serverUrl, p.serverUser, p.serverPassword)
    }

    fun testServer() {
        val subsonic = client() ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(serverBusy = true)
            val result = withContext(Dispatchers.IO) { subsonic.ping() }
            _state.value = _state.value.copy(
                serverBusy = false,
                serverStatus = if (result.ok) "connected · ${result.value}" else result.error,
            )
            if (result.ok) loadRemote()
        }
    }

    private fun loadRemote() {
        val subsonic = client() ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { subsonic.recent(300) }
            if (result.ok) {
                _state.value = _state.value.copy(remoteTracks = result.value.orEmpty())
            }
        }
    }

    // -------------------------------------------------------- queue building

    /**
     * Build a queue and play it.
     *
     * The engine runs whether or not the pool has been analysed, because its
     * two most valuable behaviours do not depend on analysis at all: the
     * per-track cooldown only needs to know when you last heard something, and
     * transition blocking only needs to know which pairs have already been
     * served. Both work on a phone full of untagged MP3s.
     *
     * What analysis buys on top is *sequencing* -- similarity, harmonic key
     * matching and tempo steps. Those terms simply contribute nothing for
     * tracks with no measurements, rather than being faked.
     */
    /**
     * Play an explicit list, in order or shuffled.
     *
     * Deliberately bypasses the queue engine. When you tap "play all" on a list
     * you are asking for *that* list -- reordering it by taste would be the app
     * overruling a direct instruction, which is the opposite of what the engine
     * is for.
     */
    fun playList(tracks: List<Track>, shuffle: Boolean) {
        val playable = tracks.filter { it.opusPath != null }
        if (playable.isEmpty()) return
        val ordered = if (shuffle) playable.shuffled() else playable
        val items = ordered.mapNotNull { mediaItemFor(it) }
        if (items.isEmpty()) return

        val reason = if (shuffle) "shuffled from this list" else "from this list"
        _state.value = _state.value.copy(
            queue = ordered.map {
                QueueEngine.Entry(it, listOf(QueueEngine.Reason(reason, 1f)), 1f)
            }
        )
        controller?.apply {
            setMediaItems(items, 0, 0L)
            prepare()
            play()
        }
    }

    fun setQueueMode(mode: QueueMode) {
        if (settings.state.value.queueMode == mode) return
        settings.setQueueMode(mode)
        _state.value = _state.value.copy(queueMode = mode)
        // Rebuild from what is playing, so the change is audible in the queue
        // immediately rather than at the next track.
        playFrom(_state.value.nowPlaying)
    }

    /** What the queue was built by, for the "playing from" line. */
    fun queueSourceLabel(): String =
        if (settings.state.value.queueMode == QueueMode.SHUFFLE) "SHUFFLE"
        else "ANTI-REPEAT QUEUE"

    fun playFrom(seed: Track?, size: Int = 40) {
        // Plain shuffle when asked for it.
        //
        // The engine is the point of the app, but there are evenings where you
        // want the library on random and no opinions about it -- and an engine
        // you cannot turn off is one you have to trust rather than choose.
        if (settings.state.value.queueMode == QueueMode.SHUFFLE) {
            val pool = (_state.value.libraryTracks + _state.value.deviceTracks)
                .filter { it.opusPath != null }
            if (pool.isNotEmpty()) {
                val ordered = listOfNotNull(seed) + (pool - setOfNotNull(seed)).shuffled()
                playList(ordered, shuffle = false)
                return
            }
        }
        playFromEngine(seed, size)
    }

    private fun playFromEngine(seed: Track?, size: Int = 40) {
        viewModelScope.launch {
            val current = _state.value

            // Pick the pool the seed belongs to, so the queue stays coherent.
            val pool = when {
                seed?.isDevice == true -> current.deviceTracks
                seed?.isRemote == true -> current.remoteTracks
                current.libraryTracks.isNotEmpty() -> current.libraryTracks
                current.deviceTracks.isNotEmpty() -> current.deviceTracks
                else -> current.remoteTracks
            }.filter { it.opusPath != null }

            if (pool.isEmpty()) {
                seed?.let { playDirect(it) }
                return@launch
            }

            // Mood only discriminates when there is something measured to
            // discriminate on; against a pool of 0.5s it would just be noise.
            val analysed = pool.count { it.isAnalysed }
            val mood = if (analysed > 0) current.mood else null

            val snapshot = withContext(Dispatchers.IO) { history.snapshot() }
            val entries = withContext(Dispatchers.Default) {
                QueueEngine(
                    pool, snapshot, moodMap(),
                    skipModel = skipModel, output = _state.value.output,
                ).build(
                    seedTrack = seed,
                    size = size.coerceAtMost(pool.size),
                    adventure = current.adventure,
                    mood = mood,
                    languageFilter = current.languageFilter,
                    targetMoods = current.targetMoods,
                )
            }
            if (entries.isEmpty()) {
                seed?.let { playDirect(it) }
                return@launch
            }

            val ordered = if (seed != null) listOf(seed) + entries.map { it.track }
                          else entries.map { it.track }
            val queueEntries = if (seed != null) {
                listOf(
                    QueueEngine.Entry(seed, listOf(QueueEngine.Reason("you picked this", 1f)), 1f)
                ) + entries
            } else entries

            // Record the path before serving it, so it is never served again.
            withContext(Dispatchers.IO) {
                history.recordTransitions(ordered.zipWithNext { a, b -> a.id to b.id })
            }

            val items = ordered.mapNotNull { mediaItemFor(it) }
            if (items.isEmpty()) return@launch

            _state.value = _state.value.copy(queue = queueEntries)
            sessionId = UUID.randomUUID().toString()

            controller?.apply {
                setMediaItems(items, 0, 0L)
                prepare()
                play()
            }
        }
    }

    /**
     * Last-resort path: play a track with the rest of its own source behind it.
     *
     * The sibling list matters. Falling back to a single-item list here meant an
     * imported-but-unanalysed track played alone, so next and previous had
     * nothing to move to and appeared broken.
     */
    private fun playDirect(track: Track) {
        val siblings = when {
            track.isDevice -> _state.value.deviceTracks
            track.isRemote -> _state.value.remoteTracks
            else -> _state.value.libraryTracks
        }.filter { it.opusPath != null }.ifEmpty { listOf(track) }
        val start = siblings.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val items = siblings.mapNotNull { mediaItemFor(it) }
        if (items.isEmpty()) return

        _state.value = _state.value.copy(
            queue = siblings.map {
                QueueEngine.Entry(it, listOf(QueueEngine.Reason("from this folder", 1f)), 1f)
            }
        )
        controller?.apply {
            setMediaItems(items, start, 0L)
            prepare()
            play()
        }
    }

    /** Turn a track into something the player can open, whatever its source. */
    private fun mediaItemFor(track: Track): MediaItem? = when {
        track.isRemote -> {
            val subsonic = client()
            val id = track.remoteId
            if (subsonic == null || id == null) null else {
                MediaItem.Builder()
                    .setMediaId(track.id.toString())
                    .setUri(subsonic.streamUrl(id))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.displayTitle)
                            .setArtist(track.displayArtist)
                            .setAlbumTitle(track.album)
                            .build()
                    )
                    .build()
            }
        }

        track.isDevice -> MediaItem.Builder()
            .setMediaId(track.id.toString())
            // Already a MediaStore content URI, not a filesystem path.
            .setUri(Uri.parse(track.opusPath ?: return null))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.displayTitle)
                    .setArtist(track.displayArtist)
                    .setAlbumTitle(track.album)
                    .build()
            )
            .build()

        else -> library.toMediaItem(track)
    }

    fun rebuildQueue() = playFrom(_state.value.nowPlaying?.takeIf { it.isAnalysed })

    /** Available mood words, so the UI only offers ones that match something. */
    fun knownMoods(): Set<String> = (moodTags.values + userMoods.values)
        .flatten().toSet()

    fun setAdventure(value: Float) {
        _state.value = _state.value.copy(adventure = value.coerceIn(0f, 1f))
    }

    fun setMood(mood: QueueEngine.Mood?) {
        _state.value = _state.value.copy(mood = mood)
    }

    /**
     * Steer the queue toward a set of moods, e.g. a "calm" session.
     *
     * Words rather than coordinates: they match both the analyser's labels and
     * yours, so a session you shaped by hand behaves the same as one the app
     * derived. Rebuilds the tail immediately -- a session button that only took
     * effect on the next track would feel broken.
     */
    fun startSession(moods: Set<String>) {
        _state.value = _state.value.copy(targetMoods = moods, mood = null)
        if (_state.value.nowPlaying != null) rebuildTail() else playFrom(null)
    }

    fun clearSession() {
        _state.value = _state.value.copy(targetMoods = emptySet(), mood = null)
        // Rebuild on the way out too. Turning a mood off left the queue shaped
        // by it, so the button appeared to do nothing until the next track.
        if (_state.value.nowPlaying != null) rebuildTail() else playFrom(null)
    }

    /**
     * Replace everything after the current track, leaving playback untouched.
     *
     * The alternative -- rebuilding the whole queue -- restarts from the top and
     * cuts off whatever is playing, which makes nudging the Adventure slider
     * feel destructive rather than exploratory.
     */
    fun rebuildTail() {
        val controller = controller ?: return
        val current = _state.value
        val playing = current.nowPlaying ?: return
        val index = controller.currentMediaItemIndex

        viewModelScope.launch {
            val pool = when {
                playing.isDevice -> current.deviceTracks
                playing.isRemote -> current.remoteTracks
                else -> current.libraryTracks
            }.filter { it.opusPath != null }
            if (pool.size < 2) return@launch

            val snapshot = withContext(Dispatchers.IO) { history.snapshot() }
            val entries = withContext(Dispatchers.Default) {
                QueueEngine(
                    pool, snapshot, moodMap(),
                    skipModel = skipModel, output = _state.value.output,
                ).build(
                    seedTrack = playing,
                    size = 40.coerceAtMost(pool.size),
                    adventure = current.adventure,
                    mood = if (pool.any { it.isAnalysed }) current.mood else null,
                    targetMoods = current.targetMoods,
                )
            }
            if (entries.isEmpty()) return@launch

            withContext(Dispatchers.IO) {
                history.recordTransitions(
                    (listOf(playing) + entries.map { it.track }).zipWithNext { a, b -> a.id to b.id }
                )
            }

            val items = entries.mapNotNull { mediaItemFor(it.track) }
            if (items.isEmpty()) return@launch

            // Keep the played history intact and swap only what is ahead.
            controller.replaceMediaItems(index + 1, controller.mediaItemCount, items)
            _state.value = _state.value.copy(
                queue = listOf(
                    QueueEngine.Entry(playing, listOf(QueueEngine.Reason("playing now", 1f)), 1f)
                ) + entries
            )
        }
    }

    /** Your labels where you gave them, the analyser's everywhere else. */
    /** Analyser labels for library and phone, with your own on top. */
    private fun moodMap(): Map<Long, Set<String>> = moodTags + deviceMoods + userMoods

    // ------------------------------------------------------------- transport

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = controller?.seekTo(ms)

    /**
     * Drop a track from the queue without interrupting what is playing.
     *
     * The controller and the visible queue are two lists that have to stay in
     * step, so both are edited together. Removing the current track is refused
     * rather than handled: it would stop playback from a control whose whole
     * purpose is to change what comes *next*.
     */
    fun removeFromQueue(index: Int) {
        val queue = _state.value.queue
        if (index !in queue.indices) return
        val playing = controller?.currentMediaItemIndex ?: -1
        if (index == playing) return

        controller?.removeMediaItem(index)
        _state.value = _state.value.copy(
            queue = queue.toMutableList().also { it.removeAt(index) },
        )
        rememberSession()
    }

    /**
     * Move a track within the queue.
     *
     * The controller's playlist and the visible queue are two lists that must
     * stay in step, so both move together. Media3 handles the case where the
     * playing item is the one moved -- playback continues, only its position in
     * the running order changes.
     */
    fun moveInQueue(from: Int, to: Int) {
        val queue = _state.value.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return

        // Nothing may be moved to or from a slot at or before the one playing.
        // Those positions are already spent: Media3 accepts the move happily and
        // the song is simply never reached, so the drag appears to work and then
        // the track silently disappears.
        val playing = controller?.currentMediaItemIndex ?: -1
        if (playing >= 0 && (from <= playing || to <= playing)) return

        controller?.moveMediaItem(from, to)
        val reordered = queue.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        _state.value = _state.value.copy(queue = reordered)
        rememberSession()
    }

    fun playQueueIndex(index: Int) {
        controller?.seekTo(index, 0L)
        controller?.play()
    }

    // ---------------------------------------------------------------- search

    fun search(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    // ----------------------------------------------------------- sleep timer

    fun setSleepTimer(minutes: Int?) {
        _state.value = _state.value.copy(
            sleepTimerEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        )
    }

    private fun checkSleepTimer() {
        val endsAt = _state.value.sleepTimerEndsAt ?: return
        if (System.currentTimeMillis() >= endsAt) {
            controller?.pause()
            _state.value = _state.value.copy(sleepTimerEndsAt = null)
        }
    }

    // --------------------------------------------------------- event capture

    private fun openEvent(trackId: Long) {
        openTrackId = trackId
        openStartedAt = System.currentTimeMillis() / 1000
        openStartPosition = controller?.currentPosition ?: 0L
    }

    private fun closeOpenEvent() {
        val trackId = openTrackId ?: return
        // Only analysed tracks feed the engine; recording plays for tracks it
        // will never sequence would skew the cooldown model for no benefit.
        if (trackId < 0) { openTrackId = null; return }

        val played = ((controller?.currentPosition ?: 0L) - openStartPosition).coerceAtLeast(0L)
        val duration = _state.value.tracks.firstOrNull { it.id == trackId }?.durationMs ?: 0L
        val output = _state.value.output

        openTrackId = null
        if (played < 1000) return

        viewModelScope.launch(Dispatchers.IO) {
            history.record(
                trackId = trackId,
                previousTrackId = previousTrackId,
                sessionId = sessionId,
                startedAt = openStartedAt,
                msPlayed = played,
                durationMs = duration,
                output = output,
            )
            previousTrackId = trackId
            val snapshot = history.snapshot()
            _state.value = _state.value.copy(
                historyEvents = snapshot.totalEvents,
                playedTrackIds = snapshot.lastPlayedAt.keys,
                pendingSync = history.pendingSyncCount(),
            )
        }
    }

    private fun seekPastIntro(mediaItem: MediaItem?) {
        val introEnd = mediaItem?.mediaMetadata?.extras
            ?.getLong(net.otozine.player.playback.PlaybackService.EXTRA_INTRO_END_MS, 0L) ?: 0L
        if (introEnd > 400L) controller?.seekTo(introEnd)
    }

    fun moodOf(track: Track): Pair<Float, Float> =
        Features.valenceOf(track) to track.energy.coerceIn(0f, 1f)

    override fun onCleared() {
        closeOpenEvent()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        history.close()
    }
}
