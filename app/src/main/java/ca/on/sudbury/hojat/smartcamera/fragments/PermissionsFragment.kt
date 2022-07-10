package ca.on.sudbury.hojat.smartcamera.fragments

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import ca.on.sudbury.hojat.smartcamera.utils.Constants

/**
 * This fragment asks user to grant app's needed permissions.
 * Once granted, it leads to CameraFragment.
 */
class PermissionsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions(requireContext())) {
            // at least 1 of required permissions isn't granted
            ActivityCompat.requestPermissions(
                requireActivity(),
                Constants.REQUIRED_PERMISSIONS,
                Constants.REQUEST_CODE_PERMISSIONS
            )
        } else {
            // all is good
            navigateToCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun navigateToCamera() {
        TODO("Needs to be done with coroutines and navigation component")
    }

    companion object {

        /**
         * static method for checking if all the permissions
         * required by this app are granted.
         */
        fun hasPermissions(context: Context) = Constants.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}