package net.otozine.player.library

/**
 * Filename metadata recovery, on device.
 *
 * A compact port of the Librarian's parser (`stages/nameparse.py`), carrying the
 * two ideas that actually matter for downloaded music:
 *
 *  1. **Known names are recognised anywhere in the string**, not just in a
 *     delimited segment. Real filenames run the fields together:
 *     `Pakkam Vanthu - Video Song Kaththi Vijay Samantha Anirudh Ravichander`.
 *  2. **Junk phrases are delimiters, not noise.** "Video Song" sits exactly
 *     between the song title and the film name, so deleting it as noise -- the
 *     obvious thing to do -- destroys the only structure the name has.
 *
 * The PC parser has a much larger alias table and an orthographic language
 * scorer. This keeps the common cases; anything it misses is still playable,
 * just less well labelled.
 */
object NameParser {

    data class Parsed(
        val title: String?,
        val artist: String?,
        val composer: String?,
        val album: String?,
        val year: Int?,
        val trackNo: Int?,
    )

    // Music directors, with the spellings that actually appear in downloads.
    private val COMPOSERS = mapOf(
        "anirudh ravichander" to "Anirudh Ravichander",
        "anirudh" to "Anirudh Ravichander",
        "a r rahman" to "A. R. Rahman",
        "ar rahman" to "A. R. Rahman",
        "arrahman" to "A. R. Rahman",
        "rahman" to "A. R. Rahman",
        "ilaiyaraaja" to "Ilaiyaraaja",
        "ilayaraja" to "Ilaiyaraaja",
        "illayaraja" to "Ilaiyaraaja",
        "yuvan shankar raja" to "Yuvan Shankar Raja",
        "yuvanshankar raja" to "Yuvan Shankar Raja",
        "yuvan" to "Yuvan Shankar Raja",
        "harris jayaraj" to "Harris Jayaraj",
        "d imman" to "D. Imman",
        "imman" to "D. Imman",
        "santhosh narayanan" to "Santhosh Narayanan",
        "gv prakash kumar" to "G. V. Prakash Kumar",
        "g v prakash kumar" to "G. V. Prakash Kumar",
        "gv prakash" to "G. V. Prakash Kumar",
        "vidyasagar" to "Vidyasagar",
        "devi sri prasad" to "Devi Sri Prasad",
        "thaman" to "Thaman S",
        "sean roldan" to "Sean Roldan",
        "govind vasantha" to "Govind Vasantha",
        "justin prabhakaran" to "Justin Prabhakaran",
        "hiphop tamizha" to "Hiphop Tamizha",
        "ghibran" to "Ghibran",
        "leon james" to "Leon James",
        "darbuka siva" to "Darbuka Siva",
        "sam cs" to "Sam C. S.",
        "karthik raja" to "Karthik Raja",
        "deva" to "Deva",
        "vijay antony" to "Vijay Antony",
        "dhibu ninan thomas" to "Dhibu Ninan Thomas",
        "sai abhyankkar" to "Sai Abhyankkar",
        "masala coffee" to "Masala Coffee",
        "jakes bejoy" to "Jakes Bejoy",
        "nivas k prasanna" to "Nivas K. Prasanna",
    )

    private val SINGERS = mapOf(
        "sid sriram" to "Sid Sriram",
        "shreya ghoshal" to "Shreya Ghoshal",
        "chinmayi" to "Chinmayi",
        "hariharan" to "Hariharan",
        "haricharan" to "Haricharan",
        "karthik" to "Karthik",
        "benny dayal" to "Benny Dayal",
        "shweta mohan" to "Shweta Mohan",
        "andrea jeremiah" to "Andrea Jeremiah",
        "jonita gandhi" to "Jonita Gandhi",
        "pradeep kumar" to "Pradeep Kumar",
        "anthony daasan" to "Anthony Daasan",
        "sathyaprakash" to "Sathyaprakash",
        "dhanush" to "Dhanush",
        "spb" to "S. P. Balasubrahmanyam",
        "s janaki" to "S. Janaki",
        "ks chithra" to "K. S. Chithra",
        "chithra" to "K. S. Chithra",
        "shakthisree gopalan" to "Shakthisree Gopalan",
        "sanah moidutty" to "Sanah Moidutty",
        "kapil kapilan" to "Kapil Kapilan",
    )

    // Cast, directors and studios: they identify the film, never the music, so
    // they are removed rather than assigned to a field.
    private val NOISE_NAMES = listOf(
        "thalapathy vijay", "vijay sethupathi", "samantha ruth prabhu", "kajal agarwal",
        "kajal aggarwal", "shruti haasan", "nayanthara", "keerthy suresh", "sai pallavi",
        "dulquer salmaan", "nivin pauly", "nazriya nazim", "sivakarthikeyan", "rajinikanth",
        "kamal haasan", "suriya", "vikram", "karthi", "jiiva", "arya", "atharvaa",
        "vishal", "santhanam", "yogi babu", "soori", "vadivelu", "trisha", "tamannaah",
        "anushka shetty", "rashmika mandanna", "wamiqa gabbi", "rj balaji", "sathish",
        "lokesh kanagaraj", "a r murugadoss", "ar murugadoss", "vignesh shivan",
        "alphonse puthren", "arun matheswaran", "atlee", "shankar", "mani ratnam",
        "vetrimaaran", "pa ranjith", "karthik subbaraj", "gautham vasudev menon",
        "sun pictures", "dream warrior pictures", "think indie", "think music",
        "sony music south", "sony music india", "lyca productions", "zee music company",
        "saregama", "divo", "wunderbar films", "2d entertainment", "seven screen studio",
        "ags entertainment", "red giant movies", "studio green", "trend music",
    )

    /** Phrases that sit BETWEEN the song title and the film name. */
    private val SPLIT_JUNK = listOf(
        "official video song", "official lyrical video", "official lyric video",
        "official music video", "official video", "official audio", "official song",
        "full video song", "full audio song", "full video", "full audio", "full song",
        "lyrical video song", "lyric video song", "lyrical video", "lyric video",
        "music video", "video song", "audio song", "song video", "video", "audio",
    )

    /** Promotional tails, always after the useful fields. */
    private val TRAILING_JUNK = listOf(
        "super hit tamil song", "super hit song", "latest tamil song", "new tamil song",
        "super hit", "hit song", "extended version", "extended", "whatsapp status",
        "tamil", "telugu", "hindi", "malayalam", "kannada", "hd", "4k", "song", "songs",
        "promo", "teaser", "making",
    )

    private val SITE_JUNK = listOf(
        "isaimini", "masstamilan", "mass tamilan", "starmusiq", "tamilwire", "kuttyweb",
        "sensongs", "naasongs", "pagalworld", "songspk", "wapking", "webmusic",
        "tamilanda", "tamiltunes", "isaidub", "moviesda", "tamilyogi", "madrasrockers",
        "ytmp3free", "y2mate", "ytmp3", "savefrom", "mp3juice", "tamilrockers",
        "320kbps", "256kbps", "192kbps", "128kbps", "kbps", "cdrip", "hq", "mp3",
    )

    private val YEAR = Regex("\\b(19[3-9]\\d|20[0-4]\\d)\\b")
    private val TRACK_NO = Regex("^\\s*(\\d{1,3})\\s*[-._)]\\s*")
    private val DOMAIN = Regex("\\b(?:www\\.)?[a-z0-9-]+\\.(?:com|net|in|org|cc|me|xyz)\\b", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("[\\[(\\{][^\\])\\}]*[\\])\\}]")
    private val WS = Regex("\\s+")

    /**
     * Strip rip-site branding from a title that came from a tag.
     *
     * Filenames go through the full parser, but embedded tags were trusted as
     * authored -- and on a library of rips they are not. The uploader writes
     * "Aaruyire - MassTamilan.com" into the title field, so every screen showed
     * the site name, and in narrow places like the cover shelf the actual song
     * name was pushed out of view by advertising.
     *
     * Only junk is removed. If that leaves nothing, the original is kept: a
     * wrong title beats a blank one.
     */
    fun stripSiteJunk(title: String): String {
        var text = DOMAIN.replace(title, " ")
        text = BRACKETS.replace(text, " ")
        SITE_JUNK.sortedByDescending { it.length }.forEach { junk ->
            text = Regex("(?<![a-z0-9])${Regex.escape(junk)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
                .replace(text, " ")
        }
        // Separators orphaned by the removal, e.g. "Song - " or "Song |".
        text = WS.replace(text, " ").trim().trim('-', '|', '~', '_', '.', ',').trim()
        return text.ifBlank { title.trim() }
    }

    fun parse(filename: String): Parsed {
        var stem = filename.substringBeforeLast('.')
        var year: Int? = null
        var trackNo: Int? = null

        // Leading track number, before junk removal eats the digits.
        TRACK_NO.find(stem)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it in 1..999) trackNo = it }
            stem = stem.removeRange(m.range)
        }

        // Slug form from converter sites: hyphens join words, not fields.
        if (!stem.contains(' ') && stem.count { it == '-' } >= 3 && stem == stem.lowercase()) {
            stem = stem.replace('-', ' ')
        }
        stem = stem.replace('_', ' ')

        // Brackets: keep the year, drop the rest -- almost always quality tags.
        stem = BRACKETS.replace(stem) { m ->
            YEAR.find(m.value)?.let { year = it.groupValues[1].toIntOrNull() }
            " "
        }
        if (year == null) {
            YEAR.find(stem)?.let {
                year = it.groupValues[1].toIntOrNull()
                stem = stem.removeRange(it.range)
            }
        }

        stem = DOMAIN.replace(stem, " ")
        SITE_JUNK.sortedByDescending { it.length }.forEach { junk ->
            stem = Regex("(?<![a-z0-9])${Regex.escape(junk)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
                .replace(stem, " ")
        }

        // Recognise names anywhere, and remove them from the running text.
        var composer: String? = null
        var artist: String? = null

        for ((alias, canonical) in COMPOSERS.entries.sortedByDescending { it.key.length }) {
            val hit = flexible(alias).find(stem) ?: continue
            if (composer == null) composer = canonical
            stem = stem.removeRange(hit.range)
        }
        for ((alias, canonical) in SINGERS.entries.sortedByDescending { it.key.length }) {
            val hit = flexible(alias).find(stem) ?: continue
            if (artist == null) artist = canonical
            stem = stem.removeRange(hit.range)
        }
        for (name in NOISE_NAMES.sortedByDescending { it.length }) {
            stem = flexible(name).replace(stem, " ")
        }

        // Split on junk phrases and dashes, remembering which did the splitting.
        var marked = stem
        SPLIT_JUNK.sortedByDescending { it.length }.forEach { junk ->
            marked = flexible(junk).replace(marked, JUNK_MARK)
        }
        marked = Regex("\\s*[-–—|~]\\s*").replace(marked, DASH_MARK)

        // Scanned character by character rather than split(), because we need to
        // know *which* separator preceded each fragment -- a plain split throws
        // that away, and it is the whole signal.
        val fragments = ArrayList<String>()
        val junkBoundaries = HashSet<Int>()
        var pendingJunk = false
        var buffer = StringBuilder()
        var i = 0
        while (i < marked.length) {
            when {
                marked.startsWith(JUNK_MARK, i) -> {
                    flush(buffer, fragments, junkBoundaries, pendingJunk)?.let { pendingJunk = false }
                    pendingJunk = true
                    buffer = StringBuilder()
                    i += JUNK_MARK.length
                }
                marked.startsWith(DASH_MARK, i) -> {
                    if (flush(buffer, fragments, junkBoundaries, pendingJunk) != null) pendingJunk = false
                    buffer = StringBuilder()
                    i += DASH_MARK.length
                }
                else -> { buffer.append(marked[i]); i++ }
            }
        }
        if (flush(buffer, fragments, junkBoundaries, pendingJunk) != null) pendingJunk = false
        if (pendingJunk) junkBoundaries.add(fragments.size)

        val cleaned = fragments.map { stripTrailing(it) }.filter { it.isNotBlank() }

        var title: String? = null
        var album: String? = null

        if (junkBoundaries.isNotEmpty() && cleaned.isNotEmpty()) {
            // A junk phrase marks the END of the song title, wherever it fell.
            val boundary = junkBoundaries.min().coerceAtMost(cleaned.size)
            if (boundary == 0) {
                title = cleaned.getOrNull(0)
                album = cleaned.getOrNull(1)
            } else {
                title = cleaned.getOrNull(boundary - 1)
                album = cleaned.getOrNull(boundary) ?: cleaned.getOrNull(boundary - 2)
            }
        } else {
            when (cleaned.size) {
                0 -> {}
                1 -> title = cleaned[0]
                else -> {
                    // Tamil rips read "Song - Movie"; Western ones "Artist - Title".
                    if (composer != null) {
                        title = cleaned[0]; album = cleaned[1]
                    } else {
                        artist = artist ?: cleaned[0]; title = cleaned[1]
                    }
                }
            }
        }

        if (artist == null && composer != null) artist = composer

        return Parsed(
            title = title?.let { tidy(it) },
            artist = artist?.let { tidy(it) },
            composer = composer,
            album = album?.let { tidy(it) },
            year = year,
            trackNo = trackNo,
        )
    }

    private const val JUNK_MARK = ""
    private const val DASH_MARK = ""

    private fun flush(
        buffer: StringBuilder,
        into: MutableList<String>,
        boundaries: MutableSet<Int>,
        pendingJunk: Boolean,
    ): Unit? {
        val text = WS.replace(buffer.toString(), " ").trim(' ', ',', '-', '|', '~', '.')
        if (text.isBlank()) return null
        if (pendingJunk) boundaries.add(into.size)
        into.add(text)
        return Unit
    }

    /** Matches a normalised phrase against real punctuation: "A.R. Rahman". */
    private fun flexible(phrase: String): Regex {
        val body = phrase.split(" ").joinToString("[\\s._-]*") { Regex.escape(it) }
        return Regex("(?<![a-zA-Z0-9])$body(?![a-zA-Z0-9])", RegexOption.IGNORE_CASE)
    }

    private fun stripTrailing(text: String): String {
        var out = text
        repeat(4) {
            val before = out
            TRAILING_JUNK.sortedByDescending { it.length }.forEach { junk ->
                out = Regex("[\\s,|~-]*(?<![a-zA-Z0-9])${Regex.escape(junk)}\\s*$", RegexOption.IGNORE_CASE)
                    .replace(out, "")
            }
            out = out.trim(' ', ',', '-', '|', '~', '.')
            if (out == before) return out
        }
        return out
    }

    /** Title-case shouty filenames, but leave short acronyms alone. */
    private fun tidy(text: String): String {
        val cleaned = WS.replace(text, " ").trim(' ', '-', '.', ',', '_')
        val letters = cleaned.filter { it.isLetter() }
        if (letters.isEmpty()) return cleaned
        val allUpper = letters.all { it.isUpperCase() }
        if (allUpper && letters.length <= 4) return cleaned
        return if (allUpper || letters.all { it.isLowerCase() }) {
            cleaned.split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
        } else cleaned
    }
}
