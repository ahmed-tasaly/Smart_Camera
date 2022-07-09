package ca.on.sudbury.hojat.smartcamera

import android.app.Dialog
import android.view.WindowManager


/**
 * It's the same as [Dialog.show] but it also sets the dialog into immersive mode.
 */
fun Dialog.showImmersive() {

    // set the dialog to not focusable.
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )

    // making sure that the dialog window is in immersive mode
    // without any interruption from system or other apps.
    window?.decorView?.systemUiVisibility = Constants.FLAG_IMMERSIVE_VIEW

    // show the dialog in immersive mode
    show()

    // set the dialog to focusable again.
    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

}
