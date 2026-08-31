package com.firas.googlephotosdownloader

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DownloaderScreen()
                }
            }
        }
    }

    private fun extractTakeout(uri: Uri, onProgress: (Int) -> Unit, onDone: (Int, String?) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var count = 0
            var error: String? = null
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && isMedia(entry.name)) {
                                val safeName = entry.name.substringAfterLast('/').ifBlank { "photo_$count" }
                                val mime = mimeFor(safeName)
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GooglePhotosDownloader")
                                }
                                val collection = if (mime.startsWith("video/")) {
                                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                } else {
                                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                }
                                val output = contentResolver.insert(collection, values)
                                if (output != null) {
                                    try {
                                        contentResolver.openOutputStream(output)?.use { out -> zip.copyTo(out) }
                                        count++
                                        withContext(Dispatchers.Main) { onProgress(count) }
                                    } catch (e: Exception) {
                                        contentResolver.delete(output, null, null)
                                        throw e
                                    }
                                } else {
                                    zip.skipEntry()
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: throw IllegalStateException("Unable to open the selected ZIP file")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main) { onDone(count, error) }
        }
    }

    private fun isMedia(name: String): Boolean = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "mp4", "m4v", "mov", "avi", "mkv", "webm" -> true
        else -> false
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"
        "m4v" -> "video/x-m4v"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    private fun ZipInputStream.skipEntry() {
        val buffer = ByteArray(8192)
        while (read(buffer) != -1) { }
    }

    @androidx.compose.runtime.Composable
    private fun DownloaderScreen() {
        var selected by remember { mutableStateOf<Uri?>(null) }
        var count by remember { mutableIntStateOf(0) }
        var running by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var message by remember { mutableStateOf("اختر ملف Google Takeout بصيغة ZIP") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            selected = uri
            if (uri != null) message = "تم اختيار الأرشيف. اضغط بدء الاستخراج."
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text("Google Photos Downloader", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("انقل الصور والفيديوهات من أرشيف Google Takeout مباشرة إلى الهاتف.")
            Spacer(Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(message)
                    if (running) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("تم استخراج $count ملف")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !running, onClick = {
                    picker.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text("اختيار ZIP") }

                Button(enabled = selected != null && !running, onClick = {
                    running = true
                    count = 0
                    progress = 0f
                    message = "جاري استخراج الملفات..."
                    extractTakeout(selected!!, { extracted ->
                        count = extracted
                        progress = 0f
                    }) { total, error ->
                        running = false
                        count = total
                        message = if (error == null) "اكتمل! تم حفظ $total ملف في Pictures/GooglePhotosDownloader" else "توقف بعد $total ملف: $error"
                    }
                }) { Text("بدء الاستخراج") }
            }

            Spacer(Modifier.height(24.dp))
            Text("ملاحظة: هذه النسخة الأولى تستخرج JPG/PNG/HEIC وغيرها من الصور وMP4/MOV وغيرها من الفيديوهات من ملفات ZIP.")
        }
    }
}
