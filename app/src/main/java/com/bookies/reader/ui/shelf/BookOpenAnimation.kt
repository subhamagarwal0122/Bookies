package com.bookies.reader.ui.shelf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The cover swinging open on its spine.
 *
 * Three details make the difference between this reading as a book and reading as a
 * rotating rectangle:
 *
 *  - **Hinge placement.** `transformOrigin` at x = 0 puts the axis on the spine edge.
 *    The default (0.5, 0.5) spins the cover about its middle, which looks like a card
 *    flip, not a book.
 *
 *  - **Perspective.** `cameraDistance` defaults to a value tuned for small UI elements
 *    and leaves a book-sized rotation looking flat. Lower values exaggerate it; 6–10×
 *    density is about right for something the size of a paperback.
 *
 *  - **The back of the cover.** Past 90° you are looking at the reverse face, which the
 *    GPU renders as a mirror image of the front. Swapping in an endpaper at the halfway
 *    point is what stops the title text appearing backwards.
 */
@Composable
fun BookOpenTransition(
    progress: Float,
    modifier: Modifier = Modifier,
    /** Shown underneath, revealed as the cover swings away. */
    page: @Composable () -> Unit,
    cover: @Composable () -> Unit,
    endpaper: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color(0xFFF3EDE2))) }
) {
    Box(modifier) {
        page()

        // Stop short of a full 180°: a cover laid perfectly flat loses all sense of
        // depth, and the slight residual angle keeps the spine readable.
        val angle = -170f * progress.coerceIn(0f, 1f)
        val showingBack = progress > 0.5f

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = angle
                    cameraDistance = 8f * density
                }
                // The page beneath should darken slightly while the cover shades it.
                .drawWithContent {
                    drawContent()
                    if (!showingBack) {
                        drawRect(Color.Black.copy(alpha = 0.25f * progress))
                    }
                }
        ) {
            if (showingBack) {
                // Counter-flip so the endpaper is not mirrored.
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { endpaper() }
            } else {
                cover()
            }
        }
    }
}

/**
 * Drives [BookOpenTransition] for a book that may need fetching from Drive first.
 *
 * Rather than covering the screen with a spinner, the cover opens partway and waits
 * there while the bundle downloads. A three second restore stops feeling like latency
 * and starts feeling like reaching for a book on a shelf.
 */
@Composable
fun rememberBookOpenProgress(
    opening: Boolean,
    restoreProgress: Float?,   // null when the book is already local
    onOpened: () -> Unit
): Float {
    val animatable = remember { Animatable(0f) }
    val onOpenedState by rememberUpdatedState(onOpened)

    /** How far the cover lifts while waiting on the network. */
    val holdAt = 0.3f

    LaunchedEffect(opening, restoreProgress) {
        when {
            !opening -> animatable.animateTo(0f, tween(220, easing = FastOutSlowInEasing))

            restoreProgress != null && restoreProgress < 1f -> {
                // Creep from the hold point in step with the download so the motion
                // reads as progress rather than a stall.
                val target = holdAt + (0.35f * restoreProgress)
                animatable.animateTo(target, tween(300, easing = FastOutSlowInEasing))
            }

            else -> {
                animatable.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                onOpenedState()
            }
        }
    }
    return animatable.value
}
