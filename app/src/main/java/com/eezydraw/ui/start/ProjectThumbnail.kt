package com.eezydraw.ui.start

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local preview with a document monogram when a preview is absent, unreadable, or corrupt. */
@Composable
fun ProjectThumbnail(project: RecentProject, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val source = project.thumbnailUri
    val loaded by produceState<LoadedThumbnail?>(null, context, source) {
        value = null
        if (source != null) {
            value = withContext(Dispatchers.IO) {
                decodeLocalThumbnail(context, source)?.let { LoadedThumbnail(source, it.asImageBitmap()) }
            }
        }
    }
    // A changed URI must never display the preceding project's bitmap while its load starts.
    val image = loaded?.takeIf { it.source == source }?.image
    val description = stringResource(R.string.project_thumbnail_description, project.name)
    val initials = remember(project.name) { projectInitials(project.name) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .65f),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = initials,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private data class LoadedThumbnail(val source: String, val image: ImageBitmap)

/** Bounds-first decoding is intentionally restricted to local URIs and runs on the IO dispatcher. */
internal fun decodeLocalThumbnail(context: Context, source: String): Bitmap? {
    return try {
        val uri = source.toUri()
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("content", "file")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth.toLong() > 512L * sampleSize || bounds.outHeight.toLong() > 512L * sampleSize) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        // Bitmap ownership passes to Compose. Recycle is unsafe while Image may still draw it.
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun projectInitials(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .take(2)
    .joinToString("") { String(Character.toChars(it.codePointAt(0))) }
    .uppercase(Locale.getDefault())
    .ifEmpty { "ED" }
