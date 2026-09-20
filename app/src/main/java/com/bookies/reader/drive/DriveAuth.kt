package com.bookies.reader.drive

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Obtains an OAuth access token carrying the `drive.file` scope.
 *
 * Two things that trip people up here:
 *
 *  1. Authentication and authorization are now separate APIs. Credential Manager
 *     (GetGoogleIdOption) tells you *who* the user is and returns an ID token — it does
 *     NOT give you a token you can call the Drive API with. Scoped access comes from
 *     AuthorizationClient, below. Most tutorials predate this split and use the
 *     deprecated GoogleSignInClient.
 *
 *  2. `drive.file` is a non-sensitive scope, so publishing never requires Google's
 *     OAuth verification review. Do not widen it to `drive` or `drive.readonly` for
 *     convenience — that trades a five-minute setup for a multi-week review.
 *
 * With `drive.file` the app can only see files it created itself. That is exactly what
 * the archive model needs, and it means Bookies can never read the rest of your Drive.
 */
class DriveAuth(private val context: Context) {

    companion object {
        /** Per-file access to files this app creates. Nothing else. */
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }

    /**
     * Returns an access token, or null if the user must be prompted first.
     *
     * When null is returned, [AuthorizationResult.pendingIntent] has been surfaced via
     * [onConsentRequired] and the caller should launch it, then retry.
     */
    suspend fun accessToken(
        activity: Activity,
        onConsentRequired: (android.content.IntentSender) -> Unit
    ): String? = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    // First run, or consent was revoked: the user has to approve.
                    result.pendingIntent?.intentSender?.let(onConsentRequired)
                    continuation.resume(null)
                } else {
                    continuation.resume(result.accessToken)
                }
            }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
}
