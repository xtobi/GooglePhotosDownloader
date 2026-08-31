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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
        setContent {
            MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { DownloaderScreen() } }
        }
    }

    private data class Result(val imported: Int, val skipped: Int, val failed: Int, val error: String? = null)

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
        val args = arrayOf(original, "Pictures/GooglePhotosDownloader/")
        contentResolver.query(collection, projection, selection, args, null)?.use { cursor -> if (!cursor.moveToFirst()) return original }
        for (i in 1..9999) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            val cArgs = arrayOf(candidate, "Pictures/GooglePhotosDownloader/")
            contentResolver.query(collection, projection, selection, cArgs, null)?.use { cursor -> if (!cursor.moveToFirst()) return candidate }
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

    @androidx.compose.runtime.Composable
    private fun DownloaderScreen() {
        var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var imported by remember { mutableIntStateOf(0) }; var skipped by remember { mutableIntStateOf(0) }; var zipIndex by remember { mutableIntStateOf(0) }; var totalZips by remember { mutableIntStateOf(0) }; var running by remember { mutableStateOf(false) }
        var step by remember { mutableIntStateOf(0) }
        var takeoutStep by remember { mutableIntStateOf(0) }
        var message by remember { mutableStateOf("") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selected = uris
            if (uris.isNotEmpty()) { step = 2; message = "${uris.size} fichier(s) sélectionné(s)." }
        }

        fun openTakeout() {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://takeout.google.com/")))
        }

        fun nextTakeoutStep() {
            if (takeoutStep < 7) takeoutStep++ else step = 1
        }

        val instructions = listOf(
            "Appuyez sur « Tout désélectionner »." to "Ouvrez Google Takeout.",
            "Cochez « Google Photos »." to "",
            "Appuyez sur « Tous les albums photo inclus »." to "",
            "Faites défiler vers le bas." to "",
            "Appuyez sur « Étape suivante »." to "",
            "Choisissez « Une seule exportation »." to "",
            "Appuyez sur « Créer une exportation »." to "",
            "Téléchargez les fichiers ZIP." to ""
        )

        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Top) {
            Text("Google Photos Downloader", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Transférer vos photos et vidéos sur votre téléphone")
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    when (step) {
                        0 -> {
                            Text("Télécharger vos photos", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { openTakeout(); takeoutStep = 0; step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir Google Takeout") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("J’ai déjà les fichiers ZIP") }
                        }
                        1, 2 -> {
                            Text("Sélection des fichiers", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(10.dp))
                            if (step == 1) Text("Sélectionnez les fichiers ZIP téléchargés depuis Google Takeout.") else Text(message)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Sélectionner les fichiers ZIP") }
                            Spacer(Modifier.height(8.dp))
                            Button(enabled = selected.isNotEmpty() && !running, onClick = {
                                running = true; imported = 0; skipped = 0; zipIndex = 0; totalZips = selected.size
                                extractTakeout(selected, { index, total, count, skippedCount -> zipIndex = index; totalZips = total; imported = count; skipped = skippedCount }) { result ->
                                    running = false; imported = result.imported; skipped = result.skipped
                                    message = if (result.error == null) "Transfert terminé : ${result.imported} fichier(s). ${result.skipped} déjà présent(s)." else "Transfert terminé avec quelques erreurs."
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Commencer le transfert") }
                            if (running) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("Archive $zipIndex sur $totalZips • $imported transféré(s)") }
                        }
                        3 -> {
                            Text("Étape ${takeoutStep + 1}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(16.dp))
                            Text(instructions[takeoutStep].first, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(20.dp))
                            if (takeoutStep == 0) Text("Dans Google Takeout, commencez par cette action.")
                            if (takeoutStep == 7) Text("Une fois les fichiers ZIP téléchargés, revenez ici.")
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { if (takeoutStep == 7) step = 1 else nextTakeoutStep() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (takeoutStep == 7) "J’ai téléchargé les fichiers ZIP" else "Suivant")
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Retour") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("📁 Dossier : Pictures/GooglePhotosDownloader")
        }
    }
}
