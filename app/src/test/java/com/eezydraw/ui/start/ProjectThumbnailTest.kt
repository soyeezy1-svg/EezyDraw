package com.eezydraw.ui.start

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectThumbnailTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fixtures = mutableListOf<File>()

    @After
    fun deleteFixtures() {
        fixtures.forEach(File::delete)
    }

    @Test
    fun largeLocalImageIsDecodedAsABoundedPreview() {
        val file = newFixture()
        val source = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.BLUE)
            file.outputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            // This fixture bitmap is never handed to Compose or to the decoder.
            source.recycle()
        }

        val decoded = decodeLocalThumbnail(context, Uri.fromFile(file).toString())
            ?: throw AssertionError("A valid local PNG must produce a thumbnail")
        try {
            assertTrue(decoded.width <= 512 && decoded.height <= 512)
            assertEquals(400, decoded.width)
            assertEquals(200, decoded.height)
            assertEquals(Color.BLUE, decoded.getPixel(decoded.width / 2, decoded.height / 2))
        } finally {
            // This test owns the returned bitmap; no Image composable is drawing it.
            decoded.recycle()
        }
    }

    @Test
    fun corruptAndMissingLocalImagesReturnNull() {
        val corrupt = newFixture().apply { writeText("This is not image data") }
        val missing = newFixture().apply { assertTrue(delete()) }

        assertNull(decodeLocalThumbnail(context, Uri.fromFile(corrupt).toString()))
        assertNull(decodeLocalThumbnail(context, Uri.fromFile(missing).toString()))
    }

    @Test
    fun remoteUrisAreRejectedBeforeAnyResolverAccess() {
        val noIoContext = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver {
                throw AssertionError("Remote thumbnail URIs must not trigger I/O")
            }
        }

        assertNull(decodeLocalThumbnail(noIoContext, "https://example.com/thumbnail.png"))
        assertNull(decodeLocalThumbnail(noIoContext, "http://example.com/thumbnail.png"))
    }

    private fun newFixture(): File = File.createTempFile("project-preview-", ".png", context.cacheDir)
        .also(fixtures::add)
}
