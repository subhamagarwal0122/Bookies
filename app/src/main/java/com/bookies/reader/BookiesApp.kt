package com.bookies.reader

import android.app.Application
import com.bookies.reader.data.db.BookiesDatabase
import com.bookies.reader.data.repo.FileStore
import com.bookies.reader.drive.ArchiveManager
import com.bookies.reader.drive.DriveAuth
import com.bookies.reader.drive.DriveClient
import com.bookies.reader.epub.EpubImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring. One user, one screen graph — a DI framework would be more
 * ceremony than it saves. Swap in Hilt if the graph ever outgrows this file.
 */
class BookiesApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { BookiesDatabase.get(this) }
    val files by lazy { FileStore(this) }
    val driveAuth by lazy { DriveAuth(this) }
    val importer by lazy { EpubImporter(database.books(), files) }
    val archiveManager by lazy {
        ArchiveManager(database.books(), database.annotations(), files, DriveClient())
    }

    override fun onCreate() {
        super.onCreate()
        // A transfer interrupted by process death leaves a book mid-state; put it back
        // on the side of the fence whose data we know is intact.
        scope.launch { archiveManager.recoverInterrupted() }
    }
}
