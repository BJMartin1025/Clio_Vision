package com.example.cliovision

import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta

object MockDeviceManager {

    private var mockDeviceKit: MockDeviceKitInterface? = null
    private var activeMockDevice: MockRaybanMeta? = null

    /**
     * Initializes the Kit and pairs a mock device using the official singleton pattern.
     */
    fun connectMockDevice(context: Context) {
        try {
            val kit = MockDeviceKit.getInstance(context)
            mockDeviceKit = kit

            val glasses = kit.pairRaybanMeta()
            activeMockDevice = glasses

            glasses.powerOn()
            glasses.unfold()
            glasses.don()

            Log.d("MockDevice", "0.5.0 Mock glasses paired and donned")
        } catch (e: Exception) {
            Log.e("MockDevice", "Failed to pair mock device: ${e.message}")
        }
    }

    /**
     * Uses a URI to set the camera feed, matching Meta's official test logic.
     * Pass a URI like: Uri.parse("file:///android_asset/test_video.mp4")
     */
    fun setMockCameraFeed(videoUri: Uri) {
        try {
            activeMockDevice?.let { device ->
                val cameraKit = device.getCameraKit()
                cameraKit.setCameraFeed(videoUri) // This now correctly expects a Uri
                Log.d("MockDevice", "Mock camera feed set to: $videoUri")
            } ?: Log.e("MockDevice", "No active device to set feed")
        } catch (e: Exception) {
            Log.e("MockDevice", "Error setting camera feed: ${e.message}")
        }
    }

    /**
     * Provides a captured photo via URI for testing capture logic.
     */
    fun setMockCapturedImage(imageUri: Uri) {
        activeMockDevice?.getCameraKit()?.setCapturedImage(imageUri)
        Log.d("MockDevice", "Mock captured image set to: $imageUri")
    }

    /**
     * Resets the kit and clears the paired device.
     */
    fun disconnectMockDevice() {
        mockDeviceKit?.reset()
        activeMockDevice = null
        Log.d("MockDevice", "Mock device kit reset")
    }
}