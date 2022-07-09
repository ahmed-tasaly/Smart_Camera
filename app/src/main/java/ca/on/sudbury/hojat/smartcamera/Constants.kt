package ca.on.sudbury.hojat.smartcamera

import android.Manifest
import android.view.View


object Constants {
    const val TAG = "cameraX"
    const val FILE_NAME_FORMAT = "yy-MM-dd-HH-mm-ss-SSS"
    const val REQUEST_CODE_PERMISSIONS = 123
    val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)


    /**
     * we're combining these flags into one so we can use
     * this flag to put the Activity into an immersive
     * mode without any interruption from other apps or system.
     */
    const val FLAG_IMMERSIVE_VIEW =
        View.SYSTEM_UI_FLAG_LOW_PROFILE or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION


    /**
     * these constants define the duration of animations
     * throughout our app.
     */
    const val FAST_ANIMATION_DURATION = 50L
    const val SLOW_ANIMATION_DURATION = 100L
}