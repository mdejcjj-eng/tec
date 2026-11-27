package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.CommandType
import com.example.data.DevicePreferences
import com.example.data.RoleMode
import com.example.data.SessionStatus
import com.example.data.StreamMode
import com.example.service.ImageCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Screen Control", appName)
    }

    @Test
    fun `image codec encodes and respects firestore base64 cap`() {
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val encoded = ImageCodec.encodeBitmapToBase64(bmp, maxDimension = 540, maxBytes = 220_000)
        bmp.recycle()
        assertTrue(encoded.isNotBlank())
        assertTrue(encoded.length <= 350_000)
        val decoded = ImageCodec.decodeBase64ToBitmap(encoded)
        assertNotNull(decoded)
        decoded?.recycle()
    }

    @Test
    fun `device preferences isolates deviceId when account changes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DevicePreferences(context)
        val first = prefs.getOrInitConfig("uid_alpha")
        assertTrue(first.deviceId.startsWith("dev_"))
        assertEquals(6, first.pairingCode.length)

        val sameUser = prefs.getOrInitConfig("uid_alpha")
        assertEquals(first.deviceId, sameUser.deviceId)

        val secondUser = prefs.getOrInitConfig("uid_beta")
        assertNotEquals(first.deviceId, secondUser.deviceId)
    }

    @Test
    fun `app lock pin hashing verifies exact pin and clears cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DevicePreferences(context)
        assertFalse(prefs.setAppPin("12"))
        assertTrue(prefs.setAppPin("482910"))
        assertTrue(prefs.verifyAppPin("482910"))
        assertFalse(prefs.verifyAppPin("482911"))
        assertTrue(prefs.getAppLockConfig().isPinEnabled)

        prefs.clearAppPin()
        assertFalse(prefs.verifyAppPin("482910"))
        assertFalse(prefs.getAppLockConfig().isPinEnabled)
    }

    @Test
    fun `enums fall back safely on unknown raw values`() {
        assertEquals(RoleMode.DUAL, RoleMode.fromRaw("UNKNOWN"))
        assertEquals(SessionStatus.REQUESTED, SessionStatus.fromRaw("UNKNOWN"))
        assertEquals(CommandType.TAP, CommandType.fromRaw("UNKNOWN"))
        assertEquals(StreamMode.BALANCED, StreamMode.fromRaw("UNKNOWN"))
    }
}
