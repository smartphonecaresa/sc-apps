package com.smartphonecare.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Takes one still photo with the front camera, used only for the shop's
 * "Request a photo" control. Note for whoever reads this: modern Android
 * (10+) shows its own camera-in-use indicator (a green dot / system
 * notification) whenever any app's camera is active — this is a platform
 * privacy protection that cannot be hidden from inside an app, and this
 * code makes no attempt to. The result is compressed small and handed back
 * to the web page, which stores it on the customer's own record.
 */
object PhotoCapture {

    fun capture(activity: MainActivity, onDone: (ByteArray?) -> Unit) {
        val hasCam = ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) { onDone(null); return }

        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(activity, selector, capture)

                capture.takePicture(
                    ContextCompat.getMainExecutor(activity),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bytes = imageProxyToJpeg(image, maxDim = 480, quality = 55)
                                onDone(bytes)
                            } finally {
                                image.close()
                                provider.unbindAll()
                            }
                        }

                        override fun onError(exc: ImageCaptureException) {
                            provider.unbindAll()
                            onDone(null)
                        }
                    }
                )
            } catch (e: Exception) {
                onDone(null)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun imageProxyToJpeg(image: ImageProxy, maxDim: Int, quality: Int): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        val rotated = if (image.imageInfo.rotationDegrees != 0) {
            val m = android.graphics.Matrix()
            m.postRotate(image.imageInfo.rotationDegrees.toFloat())
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } else bmp
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
