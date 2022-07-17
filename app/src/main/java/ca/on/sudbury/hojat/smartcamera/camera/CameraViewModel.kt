package ca.on.sudbury.hojat.smartcamera.camera

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.window.WindowManager
import ca.on.sudbury.hojat.smartcamera.utils.CameraTimer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService

class CameraViewModel(private val useCase: CameraUseCase) : ViewModel() {

    lateinit var windowManager: WindowManager

    lateinit var broadcastManager: LocalBroadcastManager

    // fragment sets and gets data into this variable
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK // default back lens of the camera

    var preview: Preview? = null

    var imageCapture: ImageCapture? = null

    // fragment needs to set and get the value of this image analyzer
    var imageAnalyzer: ImageAnalysis? = null

    var camera: Camera? = null

    var cameraProvider: ProcessCameraProvider? = null

    // Fragment can change this timer value
    var selectedTimer = CameraTimer.OFF

    /** Blocking camera operations are performed using this executor */
    lateinit var cameraExecutor: ExecutorService

    /** Returns true if the device has an available back camera.
     * False otherwise
     **/
    fun hasBackCamera() = cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false

    /** Returns true if the device has an available
     * front camera. False otherwise
     **/
    fun hasFrontCamera() = cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false

    // represents the connection between ViewModel
    // and UseCase (don't need it for now).
    fun sayHello() = "${useCase.sayHello()}\nwas performed in $this"

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        /** Helper function used to create a timestamped file */
        fun createFile(baseFolder: File, format: String, extension: String) =
            File(
                baseFolder, SimpleDateFormat(format, Locale.US)
                    .format(System.currentTimeMillis()) + extension
            )
    }
}