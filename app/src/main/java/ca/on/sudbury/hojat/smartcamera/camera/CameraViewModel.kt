package ca.on.sudbury.hojat.smartcamera.camera

import androidx.lifecycle.ViewModel

class CameraViewModel(val useCase: CameraUseCase) : ViewModel() {

    fun sayHello() = "${useCase.sayHello()}\nwas performed in $this"
}