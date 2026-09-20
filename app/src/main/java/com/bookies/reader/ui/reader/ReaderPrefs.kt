package com.bookies.reader.ui.reader

import android.content.Context

/**
 * How the reader lays a book out, remembered between sessions.
 *
 * SharedPreferences rather than DataStore: this is one boolean, read once as the reader
 * opens and written when the reader taps a toggle. DataStore's flow-and-coroutine
 * machinery would be more ceremony than the value is worth, and the value has to be
 * available synchronously to build the navigator's initial preferences.
 */
internal class ReaderPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /**
     * True for one continuous scroll, false for turned pages.
     *
     * Paginated is Readium's own default and is the better reading experience, so it
     * stays the default here — but it is not what everyone reaches for, and a reader who
     * swipes up and sees nothing move has no way to discover that pages turn sideways.
     */
    var scroll: Boolean
        get() = prefs.getBoolean(KEY_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_SCROLL, value).apply()

    private companion object {
        const val KEY_SCROLL = "scroll"
    }
}
