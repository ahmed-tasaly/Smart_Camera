package ca.on.sudbury.hojat.smartcamera

import android.app.Application
import timber.log.Timber

class SmartCameraApplication() : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}