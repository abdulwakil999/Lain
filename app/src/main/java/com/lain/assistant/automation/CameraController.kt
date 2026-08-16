package com.lain.assistant.automation

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-demand still capture Lain can use as "eyes" — separate from the screen-mirroring path. */
class CameraController(private val context: Context) {

    private fun granted() =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    suspend fun capturePhoto(useFrontCamera: Boolean = false): Result<Bitmap> {
        if (!granted()) return Result.failure(SecurityException("CAMERA permission not granted"))

        return try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(ProcessLifecycleOwner.get(), selector, imageCapture)

            val bitmap = suspendCancellableCoroutine<Bitmap> { cont ->
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bmp = image.toBitmap()
                            image.close()
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (cont.isActive) cont.resumeWithException(exception)
                        }
                    }
                )
            }
            provider.unbindAll()
            Result.success(bitmap)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
