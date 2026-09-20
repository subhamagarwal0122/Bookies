package com.bookies.reader.ui.reader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * The seam between Compose's `AndroidFragment` and Readium's fragment factory.
 *
 * `AndroidFragment` does not construct the fragment itself: it asks the host
 * FragmentManager's [FragmentFactory] for one by class name. Readium's navigator has no
 * no-argument constructor — it needs the [org.readium.r2.shared.publication.Publication],
 * which is only available once the EPUB has been opened — so the factory it returns from
 * `createFragmentFactory` has to be installed on the FragmentManager before the reader
 * composes.
 *
 * That leaves one hole, and it is the one this object exists to plug. A FragmentManager
 * also rebuilds its fragments during `Activity.onCreate`, long before any Compose code
 * runs, so an EpubNavigatorFragment that was on screen when the process died would be
 * re-instantiated by the *default* factory and take the app down with a
 * NoSuchMethodException before the shelf ever appeared. Installing this as the activity's
 * permanent factory makes that restore land on a harmless placeholder instead, which
 * [discardRestoredNavigator] then clears out before the real navigator goes in.
 */
internal object NavigatorFragments : FragmentFactory() {

    /** Set while a book is open; null the rest of the time. */
    var delegate: FragmentFactory? = null

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        delegate?.let { return it.instantiate(classLoader, className) }
        // No publication in hand: anything but a crash.
        if (className == EpubNavigatorFragment::class.java.name) return Placeholder()
        return super.instantiate(classLoader, className)
    }

    /** Stands in for a navigator whose publication did not survive. Renders nothing. */
    class Placeholder : Fragment()
}

/**
 * Removes any navigator left over from a previous life of this activity.
 *
 * Without this, `AndroidFragment` would find the restored fragment already sitting in its
 * container and adopt it — a placeholder showing a blank page, or a navigator pointing at
 * a publication that has since been closed.
 */
internal fun FragmentManager.discardRestoredNavigator() {
    val stale = fragments.filter { it is EpubNavigatorFragment || it is NavigatorFragments.Placeholder }
    if (stale.isEmpty()) return
    beginTransaction()
        .apply { stale.forEach { remove(it) } }
        // Allowing state loss: this runs while the reader is being set up, and failing to
        // tidy up is never worth an IllegalStateException in front of the reader.
        .commitNowAllowingStateLoss()
}
