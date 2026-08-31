package com.firas.googlephotosdownloader

import android.content.ContentValues
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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

    private data class Result(val imported: Int, val skipped: Int, val failed: Int, val error: String? = null)
    private data class TakeoutFile(val uri: Uri, val name: String, val size: Long)

    private fun extractTakeout(uris: List<Uri>, onUpdate: (Int, Int, Int, Int) -> Unit, onDone: (Result) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var imported = 0; var skipped = 0; var failed = 0; var firstError: String? = null
            uris.forEachIndexed { index, uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(BufferedInputStream(input)).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && isMedia(entry.name)) {
                                    val originalName = entry.name.substringAfterLast('/').ifBlank { "photo_$imported" }
                                    val mime = mimeFor(originalName)
                                    val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                    val name = uniqueName(collection, originalName)
                                    if (name == null) skipped++ else {
                                        val values = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GooglePhotosDownloader")
                                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                                        }
                                        val output = contentResolver.insert(collection, values)
                                        if (output != null) {
                                            try {
                                                contentResolver.openOutputStream(output)?.use { out -> zip.copyTo(out) } ?: throw IllegalStateException("Unable to open output file")
                                                contentResolver.update(output, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                                                imported++
                                            } catch (e: Exception) {
                                                contentResolver.delete(output, null, null); failed++; if (firstError == null) firstError = e.message
                                            }
                                        } else failed++
                                    }
                                }
                                zip.closeEntry(); entry = zip.nextEntry
                            }
                        }
                    } ?: throw IllegalStateException("Unable to open ZIP file")
                } catch (e: Exception) { failed++; if (firstError == null) firstError = e.message ?: e.javaClass.simpleName }
                withContext(Dispatchers.Main) { onUpdate(index + 1, uris.size, imported, skipped) }
            }
            withContext(Dispatchers.Main) { onDone(Result(imported, skipped, failed, firstError)) }
        }
    }

    private fun uniqueName(collection: Uri, original: String): String? {
        val base = original.substringBeforeLast('.', original); val ext = original.substringAfterLast('.', "")
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        fun available(name: String): Boolean {
            val args = arrayOf(name, "Pictures/GooglePhotosDownloader/")
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

    private fun scanDownloads(): List<TakeoutFile> {
        val result = mutableListOf<TakeoutFile>()
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%.zip", "%.ZIP")
        val uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        contentResolver.query(uri, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val id = cursor.getLong(idCol)
                val size = cursor.getLong(sizeCol)
                result += TakeoutFile(Uri.withAppendedPath(uri, id.toString()), name, size)
            }
        }
        return result
    }

    @Composable
    private fun DownloaderScreen() {
        var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var detected by remember { mutableStateOf<List<TakeoutFile>>(emptyList()) }
        var imported by remember { mutableIntStateOf(0) }; var skipped by remember { mutableIntStateOf(0) }; var zipIndex by remember { mutableIntStateOf(0) }; var totalZips by remember { mutableIntStateOf(0) }; var running by remember { mutableStateOf(false) }
        var screen by remember { mutableIntStateOf(0) }
        var takeoutStep by remember { mutableIntStateOf(0) }
        var message by remember { mutableStateOf("") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selected = uris
            if (uris.isNotEmpty()) screen = 2
        }
        fun openTakeout() { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://takeout.google.com/"))) }
        val instructions = listOf(
            "Appuyez sur « Tout désélectionner ».",
            "Cochez « Google Photos ».",
            "Appuyez sur « Tous les albums photo inclus ».",
            "Faites défiler vers le bas.",
            "Appuyez sur « Étape suivante ».",
            "Choisissez « Une seule exportation ».",
            "Appuyez sur « Créer une exportation ».",
            "Téléchargez les fichiers ZIP."
        )

        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Top) {
            Text("Google Photos Downloader", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp)); Text("Transférer vos photos et vidéos sur votre téléphone")
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    when (screen) {
                        0 -> {
                            Text("Télécharger vos photos", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { openTakeout(); takeoutStep = 0; screen = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir Google Takeout") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { detected = scanDownloads(); screen = 1 }, modifier = Modifier.fillMaxWidth()) { Text("J’ai déjà téléchargé les fichiers") }
                        }
                        1 -> {
                            Text("Fichiers Google Photos", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            if (detected.isEmpty()) {
                                Text("Aucun fichier ZIP trouvé dans Téléchargements.")
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Sélectionner les fichiers ZIP") }
                            } else {
                                Text("${detected.size} fichier(s) ZIP trouvé(s).")
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(Modifier.height(180.dp)) { items(detected) { file -> Text("• ${file.name} — ${formatBytes(file.size)}") } }
                                Spacer(Modifier.height(12.dp))
                                Button(enabled = !running, onClick = { selected = detected.map { it.uri }; screen = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Utiliser ces fichiers") }
                            }
                        }
                        2 -> {
                            Text("Prêt", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp)); Text("${selected.size} fichier(s) sélectionné(s).")
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(enabled = !running, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Modifier la sélection") }
                            Spacer(Modifier.height(8.dp))
                            Button(enabled = selected.isNotEmpty() && !running, onClick = {
                                running = true; imported = 0; skipped = 0; zipIndex = 0; totalZips = selected.size; message = "Transfert en cours…"
                                extractTakeout(selected, { index, total, count, skippedCount -> zipIndex = index; totalZips = total; imported = count; skipped = skippedCount }) { result ->
                                    running = false; imported = result.imported; skipped = result.skipped
                                    message = if (result.error == null) "Transfert terminé : ${result.imported} fichier(s). ${result.skipped} déjà présent(s)." else "Transfert terminé avec quelques erreurs."
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Commencer le transfert") }
                            if (running) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("Archive $zipIndex sur $totalZips • $imported transféré(s)") }
                            if (message.isNotEmpty() && !running) { Spacer(Modifier.height(12.dp)); Text(message) }
                        }
                        3 -> {
                            Text("Étape ${takeoutStep + 1} / ${instructions.size}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(18.dp)); Text(instructions[takeoutStep], style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(22.dp))
                            Button(onClick = { if (takeoutStep == instructions.lastIndex) { detected = scanDownloads(); screen = 1 } else takeoutStep++ }, modifier = Modifier.fillMaxWidth()) { Text(if (takeoutStep == instructions.lastIndex) "J’ai téléchargé les fichiers ZIP" else "Suivant") }
                            Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = { screen = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Retour") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp)); Text("📁 Dossier : Pictures/GooglePhotosDownloader")
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f Go".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f Mo".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f Ko".format(bytes / 1_000.0)
        else -> "$bytes o"
    }
}
