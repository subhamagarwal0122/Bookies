package com.bookies.reader.scan

import android.app.Activity
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * The barcode on the back of a paper book, read by the camera.
 *
 * Google Play services' *code scanner* rather than ML Kit's bundled barcode library plus
 * CameraX. It is the right trade for this app three times over: it brings its own scanning
 * activity, so there is no preview, no analyser and no lifecycle to get wrong; the
 * recognition runs inside Play services, so **no `CAMERA` permission is declared or
 * requested**, because this app never opens the camera; and the model is downloaded on
 * demand rather than shipped, which keeps the APK roughly 2 MB smaller. It is free, runs
 * on the device and needs no account — the same constraints that chose Open Library.
 *
 * The cost is a dependency on Play services being present and current. Every way that can
 * fail is reported as [Outcome.Unavailable] with something true to show the reader, whose
 * remedy is the same in all of them: type the ISBN, or add the book by hand.
 *
 * Only EAN-13 and EAN-8 are enabled. A book barcode is a Bookland EAN-13; scanning every
 * format would let a QR code on the same back cover win the race.
 */
object IsbnScanner {

    sealed interface Outcome {
        /** Raw barcode digits. Still has to survive `OpenLibrary.normaliseIsbn`. */
        data class Scanned(val raw: String) : Outcome

        /** Backed out of the scanner. Silence is the right response. */
        data object Cancelled : Outcome

        /** The scanner could not run. [reason] is shown to the reader as written. */
        data class Unavailable(val reason: String) : Outcome
    }

    fun scan(activity: Activity, onResult: (Outcome) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
            // Paperback barcodes are small and often creased; without this the reader has
            // to push the phone close enough that it cannot hold focus.
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(activity, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                // A scanner that ran but returned nothing usable is a failure, not a
                // success with an empty string — the dialog would just sit there.
                val raw = barcode.rawValue
                onResult(
                    if (raw.isNullOrBlank()) Outcome.Unavailable("That barcode could not be read")
                    else Outcome.Scanned(raw)
                )
            }
            .addOnCanceledListener { onResult(Outcome.Cancelled) }
            .addOnFailureListener { error -> onResult(failureOutcome(error)) }
    }

    /**
     * Cancellation arrives here as well as through the cancel listener — Play services
     * reports a backed-out scan as a failed [com.google.android.gms.tasks.Task] carrying
     * [MlKitException.CODE_SCANNER_CANCELLED], not as a cancelled one.
     */
    private fun failureOutcome(error: Exception): Outcome {
        val code = (error as? MlKitException)?.errorCode ?: return Outcome.Unavailable(
            "The scanner could not start — type the ISBN instead"
        )
        return when (code) {
            MlKitException.CODE_SCANNER_CANCELLED -> Outcome.Cancelled

            // The module downloads on first use. Offline, or on a device that has never
            // scanned anything, this is the one the reader actually hits.
            MlKitException.CODE_SCANNER_UNAVAILABLE -> Outcome.Unavailable(
                "The scanner is still downloading — type the ISBN, or try again in a moment"
            )
            MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD -> Outcome.Unavailable(
                "Play services is too old to scan — type the ISBN instead"
            )
            MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> Outcome.Unavailable(
                "Play services was refused the camera — type the ISBN instead"
            )
            else -> Outcome.Unavailable("The scan failed — type the ISBN instead")
        }
    }
}
