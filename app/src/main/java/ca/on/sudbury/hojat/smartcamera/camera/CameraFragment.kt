package ca.on.sudbury.hojat.smartcamera.camera

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import ca.on.sudbury.hojat.smartcamera.KEY_EVENT_ACTION
import ca.on.sudbury.hojat.smartcamera.KEY_EVENT_EXTRA
import ca.on.sudbury.hojat.smartcamera.R
import ca.on.sudbury.hojat.smartcamera.databinding.FragmentCameraBinding
import ca.on.sudbury.hojat.smartcamera.gallery.EXTENSION_WHITELIST
import ca.on.sudbury.hojat.smartcamera.utils.CameraTimer
import ca.on.sudbury.hojat.smartcamera.utils.Constants
import ca.on.sudbury.hojat.smartcamera.utils.Constants.ANIMATION_FAST_MILLIS
import ca.on.sudbury.hojat.smartcamera.utils.Constants.ANIMATION_SLOW_MILLIS
import ca.on.sudbury.hojat.smartcamera.utils.simulateClick
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

private const val PERMISSIONS_REQUEST_CODE = 10


/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val binding get() = _fragmentCameraBinding!!

    private lateinit var outputDirectory: File

    private var displayId: Int = -1

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    } // this will remain in fragment

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    binding.cameraCaptureButton.simulateClick()
                }
            }
        }
    } // this will remain in fragment

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Timber.d("Rotation changed: ${view.display.rotation}")
                vm.imageCapture?.targetRotation = view.display.rotation
                vm.imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    } // this will remain in fragment

    private val vm: CameraViewModel by viewModel()

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!CameraViewModel.hasPermissions(requireContext())) {
            askForPermissions(requireContext())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _fragmentCameraBinding = null

        // Shut down our background executor
        vm.cameraExecutor.shutdown()

        // Unregister the broadcast receivers and listeners
        vm.broadcastManager.unregisterReceiver(volumeDownReceiver)// this will remain in fragment
        displayManager.unregisterDisplayListener(displayListener)// this will remain in fragment
    }

    private fun askForPermissions(context: Context) {
        if (!CameraViewModel.hasPermissions(context)) {
            // If permissions have already been granted, proceed
            // Request camera-related permissions
            requestPermissions(Constants.PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askForPermissions(requireContext())

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (PackageManager.PERMISSION_GRANTED != grantResults.firstOrNull()) {
                // user has not granted the permissions
                Toast.makeText(
                    requireContext(),
                    "Permissions are required for the app to work correctly",
                    Toast.LENGTH_SHORT
                ).show()
                askForPermissions(requireContext())
            }
        }

    }

    private fun setGalleryThumbnail(uri: Uri) {
        // Run the operations in the view's thread
        binding.photoViewButton.let { photoViewButton ->
            photoViewButton.post {
                // Remove thumbnail padding
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                // Load thumbnail into circular button using Glide
                Glide.with(photoViewButton)
                    .load(uri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fragment = this

        // Initialize our background executor
        vm.cameraExecutor = Executors.newSingleThreadExecutor()

        vm.broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        vm.broadcastManager.registerReceiver(
            volumeDownReceiver,
            filter
        )// this will remain in fragment

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)// this will remain in fragment

        //Initialize WindowManager to retrieve display metrics
        vm.windowManager = WindowManager(view.context)

        // Determine the output directory
        outputDirectory = CameraViewModel.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        binding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = binding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            setUpCamera()
        }

        // let's see if koin works correctly here
        Toast.makeText(requireContext(), vm.sayHello(), Toast.LENGTH_SHORT).show()
    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({

            // CameraProvider
            vm.cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            vm.lensFacing = when {
                vm.hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                vm.hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }

            // Enable or disable switching between cameras
            updateCameraSwitchButton()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = vm.windowManager.getCurrentWindowMetrics().bounds
        Timber.d("Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Timber.d("Preview aspect ratio: $screenAspectRatio")

        val rotation = binding.viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = vm.cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(vm.lensFacing).build()

        // Preview
        vm.preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        vm.imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()

        // ImageAnalysis
        vm.imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()
            // The analyzer can then be assigned to the instance
            .also {
                it.setAnalyzer(vm.cameraExecutor, LuminosityAnalyzer { luma ->
                    // Values returned from our analyzer are passed to the attached listener
                    // We log image analysis results here - you should do something useful
                    // instead!
                    Timber.d("Average luminosity: $luma")
                })
            }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            vm.camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, vm.preview, vm.imageCapture, vm.imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            vm.preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (e: Exception) {
            Timber.e("Use case binding failed ${e.message}")
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - Constants.RATIO_4_3_VALUE) <= abs(previewRatio - Constants.RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.uppercase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        binding.cameraSwitchButton.isEnabled = false
    }

    fun takePicture() {
        lifecycleScope.launch(Dispatchers.Main) {
            when (vm.selectedTimer) {
                CameraTimer.SEC3 -> for (i in 3 downTo 1) {
                    binding.countdown.text = i.toString()
                    delay(1000)
                }

                CameraTimer.SEC10 -> for (i in 10 downTo 1) {
                    binding.countdown.text = i.toString()
                    delay(1000)
                }
                else -> binding.countdown.text = ""
            }
            binding.countdown.text = ""
            imagePhoto()
        }
    }

    private fun imagePhoto() {
        // Get a stable reference of the modifiable image
        // capture use case
        vm.imageCapture?.let { imageCapture ->

            // Create output file to hold the image
            val photoFile =
                CameraViewModel.createFile(
                    outputDirectory,
                    Constants.FILENAME,
                    Constants.PHOTO_EXTENSION
                )

            // Setup image capture metadata
            val metadata = Metadata().apply {

                // Mirror image when using the front camera
                isReversedHorizontal = vm.lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            // Create output options object which contains file + metadata
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, vm.cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(e: ImageCaptureException) {
                        Timber.e("Photo capture failed: ${e.message}")
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Timber.d("Photo capture succeeded: $savedUri")

                        // We can only change the foreground Drawable using API level 23+ API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Update the gallery thumbnail with latest picture taken
                            setGalleryThumbnail(savedUri)
                        }

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            requireActivity().sendBroadcast(
                                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }

                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        val mimeType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(savedUri.toFile().absolutePath),
                            arrayOf(mimeType)
                        ) { _, uri ->
                            Timber.d("Image capture scanned into media store: $uri")
                        }
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                // Display flash animation to indicate that photo was captured
                binding.root.postDelayed({
                    binding.root.foreground = ColorDrawable(Color.WHITE)
                    binding.root.postDelayed(
                        { binding.root.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    fun switchCamera() {
        vm.lensFacing = if (CameraSelector.LENS_FACING_FRONT == vm.lensFacing) {
            binding.cameraSwitchButton.setImageResource(R.drawable.ic_camera_front)
            CameraSelector.LENS_FACING_BACK
        } else {
            binding.cameraSwitchButton.setImageResource(R.drawable.ic_camera_rear)
            CameraSelector.LENS_FACING_FRONT
        }
        bindCameraUseCases()
    }

    fun showGallery() {
        if (true == outputDirectory.listFiles()?.isNotEmpty()) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(
                CameraFragmentDirections.actionCameraToGallery(
                    outputDirectory.absolutePath
                )
            )
        }
    }

    fun showTimerOptions() {
        binding.timerConteiner.visibility = View.VISIBLE
    }

    fun closeTimerAndSelect(timer: CameraTimer) {
        vm.selectedTimer = timer
        binding.timerConteiner.visibility = View.GONE
        binding.timerButton.setImageResource(setImageDrawableSelect(timer))
    }

    private fun setImageDrawableSelect(timer: CameraTimer) = when (timer) {
        CameraTimer.OFF -> R.drawable.ic_timer_off
        CameraTimer.SEC3 -> R.drawable.ic_timer_3_sec
        CameraTimer.SEC10 -> R.drawable.ic_timer_10_sec
    }

    fun showFlashOptions() {
        binding.flashConteiner.visibility = View.VISIBLE
    }

    fun closeFlashOptionsAndSelect(flashMode: Int) {
        vm.imageCapture?.flashMode = flashMode
        binding.flashConteiner.visibility = View.GONE
        binding.flashButton.setImageResource(setImageDrawableFlashMode(flashMode))
    }

    private fun setImageDrawableFlashMode(flashMode: Int) = when (flashMode) {
        ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
        ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
        ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
        else -> R.drawable.ic_flash_auto
    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            binding.cameraSwitchButton.isEnabled =
                vm.hasBackCamera() && vm.hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            binding.cameraSwitchButton.isEnabled = false
        }
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }
}
