package com.eezydraw.ui.canvas.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.*
import com.eezydraw.core.model.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Draft edits never mutate a saved definition. Cancel discards all fields and the copied photo. */
@Composable
internal fun LibraryItemEditor(initial: LibraryItem, creating: Boolean, onSave: (LibraryItem) -> Unit, onDismiss: () -> Unit) {
    var source by remember(initial) { mutableStateOf(initial) }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    var width by remember(initial) { mutableStateOf((initial.defaultSize.width * 1000).toString()) }
    var depth by remember(initial) { mutableStateOf((initial.defaultSize.depth * 1000).toString()) }
    var height by remember(initial) { mutableStateOf((initial.defaultSize.height * 1000).toString()) }
    var mountingHeight by remember(initial) { mutableStateOf((initial.defaultMountingHeight * 1000).toString()) }
    var system by remember(initial) { mutableStateOf(initial.defaultSystem) }
    var colour by remember(initial) { mutableStateOf(initial.defaultColourArgb.toString(16).uppercase()) }
    var mounting by remember(initial) { mutableStateOf(initial.mounting) }
    var error by remember { mutableStateOf<String?>(null) }
    var readingPhoto by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            readingPhoto = true
            try {
                val encoded = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= 8 * 1024 * 1024) { "Choose a photo smaller than 8 MiB." }
                            out.write(buffer,0,n)
                        }
                        out.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image could not be opened." }
                    var sample = 1
                    while (maxOf(bounds.outWidth,bounds.outHeight) / sample > 256) sample *= 2
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample }))
                    try {
                        val out = ByteArrayOutputStream()
                        check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))
                        Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP).also {
                            require(it.length <= 350_000) { "Choose a smaller photo." }
                        }
                    } finally { bitmap.recycle() }
                }
                source = source.copy(thumbnailBase64 = encoded)
                error = null
            } catch (e: Exception) { error = e.message ?: "Could not read the photo." }
            finally { readingPhoto = false }
        }
    }
    fun save(asNew: Boolean) {
        try {
            fun metres(value: String): Double = requireNotNull(value.toDoubleOrNull()) { "Enter valid dimensions in millimetres." } / 1000.0
            val dimensions = listOf(metres(width),metres(depth),metres(height))
            require(dimensions.all {it.isFinite() && it > 0}) { "Enter positive width, depth and height." }
            val size = Size3(dimensions[0],dimensions[1],dimensions[2])
            val argb = requireNotNull(colour.removePrefix("#").toLongOrNull(16)) { "Enter an ARGB colour, for example FF4477AA." }
            require(argb in 0..0xFFFFFFFFL) { "Enter an eight-digit ARGB colour." }
            val newId = if (asNew) LibraryItemId(UUID.randomUUID().toString()) else source.id
            val reservedKeys = setOf(LibraryParameterKeys.MOUNTING_HEIGHT,LibraryParameterKeys.SYSTEM,LibraryParameterKeys.COLOUR,LibraryParameterKeys.NAME_TAG)
            val next = source.copy(id=newId,name=name.trim(),category=category.trim(),defaultSize=size,
                geometrySize=source.geometrySize ?: source.defaultSize,defaultMountingHeight=metres(mountingHeight),
                defaultSystem=system.trim(),defaultColourArgb=argb,mounting=mounting,materialId=null,
                parameters=source.parameters-reservedKeys,builtIn=if(asNew) false else source.builtIn,
                version=if(asNew) 1 else source.version+1,
                fitting=source.fitting?.let { if(asNew) it.copy(baseTypeId=newId) else it })
            require(next.name.length <= 120 && next.category.length <= 120) { "Names must be at most 120 characters." }
            onSave(next)
            onDismiss()
        } catch (e: IllegalArgumentException) { error=e.message ?: "Check the item settings." }
    }
    AlertDialog(onDismissRequest=onDismiss, modifier=Modifier.testTag("library_item_editor"),
        title={Text(if(creating) "Create custom item" else "Edit item")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            LibraryThumbnail(source,Modifier.fillMaxWidth().height(64.dp))
            Text("Dimensions and mounting height in mm",style=MaterialTheme.typography.labelMedium)
            OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true,modifier=Modifier.testTag("library_edit_name"))
            OutlinedTextField(category,{category=it},label={Text("Category")},singleLine=true,modifier=Modifier.testTag("library_edit_category"))
            if(creating) {
                Text("Base shape",style=MaterialTheme.typography.labelMedium)
                LibraryChoice(LibraryBaseShape.entries.map { it.name },null) { index ->
                    source=createLibraryItem(source.id,LibraryBaseShape.entries[index]).copy(thumbnailBase64=source.thumbnailBase64)
                }
            }
            OutlinedTextField(width,{width=it},label={Text("Width (mm)")},singleLine=true,modifier=Modifier.testTag("library_edit_width"))
            OutlinedTextField(depth,{depth=it},label={Text("Depth (mm)")},singleLine=true,modifier=Modifier.testTag("library_edit_depth"))
            OutlinedTextField(height,{height=it},label={Text("Height (mm)")},singleLine=true,modifier=Modifier.testTag("library_edit_height"))
            Text("Mounting type",style=MaterialTheme.typography.labelMedium)
            LibraryChoice(MountingType.entries.map { it.name },mounting.ordinal) {mounting=MountingType.entries[it]}
            OutlinedTextField(mountingHeight,{mountingHeight=it},label={Text("Default mounting height (mm)")},singleLine=true)
            OutlinedTextField(system,{system=it},label={Text("System")},singleLine=true,modifier=Modifier.testTag("library_edit_system"))
            OutlinedTextField(colour,{colour=it},label={Text("Colour (ARGB hex)")},singleLine=true,modifier=Modifier.testTag("library_edit_colour"))
            Text("2D symbol / icon",style=MaterialTheme.typography.labelMedium)
            LibraryChoice(listOf("Keep drawing","Outline","Cross","Circle"),null) { index ->
                source=if(index==0) source.copy(symbolGeometry=initial.symbolGeometry) else {
                    // Presets use a unit envelope so they scale with the physical drawing.
                    val bounds=source.geometrySize ?: source.defaultSize
                    fun scaled(p:Point3)=Point3(p.x*bounds.width,p.y*bounds.depth)
                    val lines=when(LibraryPlanSymbol.entries[index-1]) {
                        LibraryPlanSymbol.CIRCLE -> librarySymbolGeometry(LibraryPlanSymbol.CIRCLE).map { shape ->
                            val path=shape as Shape.Polyline
                            path.copy(points=path.points.map(::scaled))
                        }
                        else -> librarySymbolGeometry(LibraryPlanSymbol.entries[index-1]).map { shape ->
                            when(shape) {
                                is Shape.Line -> Shape.Line(scaled(shape.start),scaled(shape.end))
                                is Shape.Polyline -> Shape.Polyline(shape.points.map(::scaled),shape.closed)
                                else -> shape
                            }
                        }
                    }
                    source.copy(symbolGeometry=lines)
                }
            }
            Row {
                TextButton({photo.launch(arrayOf("image/*"))},enabled=!readingPhoto,modifier=Modifier.testTag("library_choose_photo")) {Text(if(readingPhoto) "Reading photo…" else "Choose icon / photo")}
                if(source.thumbnailBase64!=null)TextButton({source=source.copy(thumbnailBase64=null)}) {Text("Remove")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("library_editor_error"))}
        }},
        confirmButton={TextButton({save(false)},enabled=!readingPhoto,modifier=Modifier.testTag("library_editor_save")) {Text("Save")}},
        dismissButton={Row {
            if(!creating)TextButton({save(true)},enabled=!readingPhoto,modifier=Modifier.testTag("library_editor_save_as_new")) {Text("Save as new item")}
            TextButton(onDismiss) {Text("Cancel")}
        }},
    )
}

@Composable
private fun LibraryChoice(labels: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    Column { labels.chunked(2).forEachIndexed { row, entries ->
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) { entries.forEachIndexed { column,label ->
            val index=row*2+column
            FilterChip(selected==index,{onSelect(index)},label={Text(label.lowercase().replaceFirstChar(Char::uppercase))})
        } }
    } }
}
