package com.firas.googlephotosdownloader

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { DownloaderScreen() } } }
    }

    private data class Result(val imported: Int, val skipped: Int, val failed: Int, val photos: Int, val videos: Int, val error: String? = null)

    private fun extractTakeout(uris: List<Uri>, onUpdate: (Int, Int, Int, Int, Int) -> Unit, onDone: (Result) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var imported = 0; var skipped = 0; var failed = 0; var photos = 0; var videos = 0; var firstError: String? = null
            uris.forEachIndexed { index, uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(BufferedInputStream(input)).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isMedia(entry.name)) {
                                    val originalName = entry.name.substringAfterLast('/').ifBlank { "media_$imported" }
                                    val mime = mimeFor(originalName)
                                    val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                    val name = uniqueName(collection, originalName)
                                    if (name == null) skipped++ else {
                                        val values = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/الباشا")
                                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                                        }
                                        val output = contentResolver.insert(collection, values)
                                        if (output != null) {
                                            try {
                                                contentResolver.openOutputStream(output)?.use { out -> zip.copyTo(out) } ?: throw IllegalStateException("Unable to open output file")
                                                contentResolver.update(output, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                                                imported++
                                                if (mime.startsWith("video/")) videos++ else photos++
                                            } catch (e: Exception) {
                                                contentResolver.delete(output, null, null); failed++; if (firstError == null) firstError = e.message
                                            }
                                        } else failed++
                                    }
                                }
                                zip.closeEntry(); entry = zip.nextEntry
                                withContext(Dispatchers.Main) { onUpdate(index + 1, uris.size, imported, photos, videos) }
                            }
                        }
                    } ?: throw IllegalStateException("Unable to open ZIP file")
                } catch (e: Exception) { failed++; if (firstError == null) firstError = e.message ?: e.javaClass.simpleName }
            }
            withContext(Dispatchers.Main) { onDone(Result(imported, skipped, failed, photos, videos, firstError)) }
        }
    }

    private fun uniqueName(collection: Uri, original: String): String? {
        val base = original.substringBeforeLast('.', original); val ext = original.substringAfterLast('.', "")
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        fun available(name: String): Boolean {
            val args = arrayOf(name, "Pictures/الباشا/")
            contentResolver.query(collection, projection, selection, args, null)?.use { if (it.moveToFirst()) return false }
            return true
        }
        if (available(original)) return original
        for (i in 1..9999) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (available(candidate)) return candidate
        }
        return null
    }

    private fun isMedia(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tif", "tiff", "mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp" -> true
        else -> false
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"; "heif" -> "image/heif"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "tif", "tiff" -> "image/tiff"; "mp4" -> "video/mp4"; "m4v" -> "video/x-m4v"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"; else -> "application/octet-stream"
    }

    @Composable
    private fun DownloaderScreen() {
        var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var running by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }
        var imported by remember { mutableIntStateOf(0) }
        var photos by remember { mutableIntStateOf(0) }
        var videos by remember { mutableIntStateOf(0) }
        var skipped by remember { mutableIntStateOf(0) }
        var failed by remember { mutableIntStateOf(0) }
        var currentZip by remember { mutableIntStateOf(0) }
        var totalZips by remember { mutableIntStateOf(0) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selected = uris
            done = false
        }

        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Top) {
            Text("الباشا", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("نقل صورك وفيديوهاتك إلى الهاتف")
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    if (done) {
                        Text("تم النقل بنجاح", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Text("$photos صورة")
                        Text("$videos فيديو")
                        Text("$imported ملف تم نقله")
                        if (skipped > 0) Text("$skipped ملف موجود من قبل — تم تجاهله")
                        if (failed > 0) Text("$failed ملف لم يتم نقله")
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { done = false; selected = emptyList() }, Modifier.fillMaxWidth()) { Text("نقل جديد") }
                    } else if (running) {
                        Text("جاري نقل الملفات…", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text("الملف $currentZip من $totalZips")
                        Text("$imported ملف تم نقله")
                        Text("$photos صورة • $videos فيديو")
                    } else {
                        Text("اختر ملف Takeout", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("اختر ملف ZIP الذي نزلته من Google Takeout.")
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth()) { Text("اختيار ملف Takeout") }
                        if (selected.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("تم اختيار ${selected.size} ملف")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                running = true; done = false; imported = 0; photos = 0; videos = 0; skipped = 0; failed = 0; currentZip = 0; totalZips = selected.size
                                extractTakeout(selected, { zip, total, count, photoCount, videoCount -> currentZip = zip; totalZips = total; imported = count; photos = photoCount; videos = videoCount }) { result ->
                                    running = false; done = true; imported = result.imported; photos = result.photos; videos = result.videos; skipped = result.skipped; failed = result.failed
                                }
                            }, Modifier.fillMaxWidth()) { Text("بدء نقل الصور") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth()) { Text("تغيير الملف") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("📁 Pictures/الباشا")
        }
    }
}
