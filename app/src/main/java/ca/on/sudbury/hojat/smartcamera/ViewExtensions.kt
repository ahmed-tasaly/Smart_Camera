package ca.on.sudbury.hojat.smartcamera

import android.os.Build
import android.view.DisplayCutout
import android.view.View
import androidx.annotation.RequiresApi
import ca.on.sudbury.hojat.smartcamera.Constants.FAST_ANIMATION_DURATION


/**
 * simulates a click to the [View] with a corresponding
 * delay time; we'll use it to trigger animations to the
 * buttons (but it can be used on any Views).
 */
fun View.simulateClick(delay: Long = FAST_ANIMATION_DURATION) {
    performClick()
    isPressed = true
    invalidate()
    postDelayed({
        invalidate()
        isPressed = false
    }, delay)
}

/**
 * Pads this view according to the insets provided by the device's
 * display cutout (i.e. notch)
 */
@RequiresApi(Build.VERSION_CODES.P)
fun View.padWithDisplayCutout() {

    // A helper method which applies padding from cutout's safe insets.
    fun doPadding(cutout: DisplayCutout) = setPadding(
        cutout.safeInsetLeft,
        cutout.safeInsetTop,
        cutout.safeInsetRight,
        cutout.safeInsetBottom
    )

    // Apply the padding with the use of display cutout designated "safe area".
    rootWindowInsets?.displayCutout?.let {
        doPadding(it)
    }

    // Setting a listener for window insets; since view.rootWindowInsets
    // may not be ready.
    setOnApplyWindowInsetsListener { _, insets ->
        insets.displayCutout?.let {
            doPadding(it)
        }
        insets
    }
}





