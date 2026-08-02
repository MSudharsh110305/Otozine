package net.otozine.player

import android.app.Application
import net.otozine.player.library.LibraryRepository

class OtoZineApp : Application() {

    /** Single library instance for the process lifetime. */
    val library: LibraryRepository by lazy { LibraryRepository(this) }

    override fun onTerminate() {
        library.close()
        super.onTerminate()
    }
}
