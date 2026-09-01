package com.firas.googlephotosdownloader

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.firas.googlephotosdownloader.ui.theme.CoralPrimary
import com.firas.googlephotosdownloader.ui.theme.ErrorRed
import com.firas.googlephotosdownloader.ui.theme.IndigoPrimary
import com.firas.googlephotosdownloader.ui.theme.PachaFotoTheme
import com.firas.googlephotosdownloader.ui.theme.SuccessGreen
import com.firas.googlephotosdownloader.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.text.NumberFormat
import java.util.Locale
import java.util.zip.ZipInputStream

data class SelectedZip(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
)

enum class TransferStep {
    SELECT,
    TRANSFERRING,
    DONE
}

data class TransferReport(
    val imported: Int,
    val photos: Int,
    val videos: Int,
    val skipped: Int,
    val failed: Int,
    val firstError: String? = null
)

class MainActivity : ComponentActivity() {

    private var transferJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PachaFotoTheme {
                PachaFotoApp(
                    onStartTransfer = { zips, onProgress, onDone ->
                        transferJob = lifecycleScope.launch(Dispatchers.IO) {
                            executeTakeoutTransfer(zips, onProgress, onDone)
                        }
                    },
                    onCancelTransfer = {
                        transferJob?.cancel()
                    }
                )
            }
        }
    }

    private suspend fun executeTakeoutTransfer(
        zips: List<SelectedZip>,
        onProgress: (zipIdx: Int, totalZips: Int, zipName: String, currentItem: String, imported: Int, photos: Int, videos: Int, skipped: Int, failed: Int, progressFraction: Float) -> Unit,
        onDone: (TransferReport) -> Unit
    ) {
        var imported = 0
        var skipped = 0
        var failed = 0
        var photos = 0
        var videos = 0
        var firstError: String? = null

        val totalZips = zips.size

        zips.forEachIndexed { index, zipItem ->
            val zipName = zipItem.name
            try {
                contentResolver.openInputStream(zipItem.uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zipStream ->
                        var entry = zipStream.nextEntry
                        var entriesInZip = 0
                        while (entry != null) {
                            entriesInZip++
                            val entryName = entry.name
                            if (!entry.isDirectory && isMedia(entryName)) {
                                val originalName = entryName.substringAfterLast('/').ifBlank { "media_$imported" }
                                val mime = mimeFor(originalName)
                                val isVideo = mime.startsWith("video/")
                                val collection = if (isVideo) {
                                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                } else {
                                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                }

                                // Check duplicate in Pictures/PachaFoto
                                val isDuplicate = fileAlreadyExists(collection, originalName)
                                if (isDuplicate) {
                                    skipped++
                                } else {
                                    val values = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, originalName)
                                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PachaFoto")
                                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                                    }

                                    val itemUri = contentResolver.insert(collection, values)
                                    if (itemUri != null) {
                                        try {
                                            contentResolver.openOutputStream(itemUri)?.use { outStream ->
                                                zipStream.copyTo(outStream)
                                            } ?: throw IllegalStateException("Impossible d'écrire le fichier")

                                            contentResolver.update(
                                                itemUri,
                                                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                                                null,
                                                null
                                            )
                                            imported++
                                            if (isVideo) videos++ else photos++
                                        } catch (e: Exception) {
                                            contentResolver.delete(itemUri, null, null)
                                            failed++
                                            if (firstError == null) firstError = e.message
                                        }
                                    } else {
                                        failed++
                                    }
                                }
                            }

                            val approxProgress = ((index.toFloat() + (entriesInZip % 100) / 100f) / totalZips.toFloat()).coerceIn(0f, 0.98f)

                            withContext(Dispatchers.Main) {
                                onProgress(
                                    index + 1,
                                    totalZips,
                                    zipName,
                                    entryName.substringAfterLast('/'),
                                    imported,
                                    photos,
                                    videos,
                                    skipped,
                                    failed,
                                    approxProgress
                                )
                            }

                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }
                } ?: throw IllegalStateException("Impossible d'ouvrir l'archive ZIP")
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
            }
        }

        withContext(Dispatchers.Main) {
            onDone(TransferReport(imported, photos, videos, skipped, failed, firstError))
        }
    }

    private fun fileAlreadyExists(collection: Uri, originalName: String): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(originalName, "Pictures/PachaFoto/")
        try {
            contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        } catch (_: Exception) {
            val selectionFallback = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val argsFallback = arrayOf(originalName)
            contentResolver.query(collection, projection, selectionFallback, argsFallback, null)?.use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        }
        return false
    }

    private fun isMedia(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tif", "tiff",
        "dng", "raw", "cr2", "nef", "arw",
        "mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp", "ts" -> true
        else -> false
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "dng" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "m4v" -> "video/x-m4v"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }
}

fun getZipDetails(context: Context, uri: Uri): SelectedZip {
    var name = "archive.zip"
    var size: Long = 0
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "archive.zip"
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
    } catch (_: Exception) {}

    if (size <= 0) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                size = pfd.statSize
            }
        } catch (_: Exception) {}
    }

    return SelectedZip(uri = uri, name = name, sizeBytes = size)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Taille inconnue"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    val format = NumberFormat.getNumberInstance(Locale.FRENCH).apply { maximumFractionDigits = 1 }
    return when {
        gb >= 1.0 -> "${format.format(gb)} Go"
        mb >= 1.0 -> "${format.format(mb)} Mo"
        kb >= 1.0 -> "${format.format(kb)} Ko"
        else -> "$bytes octets"
    }
}

fun formatNumber(number: Int): String {
    return NumberFormat.getNumberInstance(Locale.FRENCH).format(number)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PachaFotoApp(
    onStartTransfer: (
        zips: List<SelectedZip>,
        onProgress: (zipIdx: Int, totalZips: Int, zipName: String, currentItem: String, imported: Int, photos: Int, videos: Int, skipped: Int, failed: Int, progress: Float) -> Unit,
        onDone: (TransferReport) -> Unit
    ) -> Unit,
    onCancelTransfer: () -> Unit
) {
    val context = LocalContext.current
    val selectedZips = remember { mutableStateListOf<SelectedZip>() }

    var step by remember { mutableStateOf(TransferStep.SELECT) }
    var currentZipIdx by remember { mutableIntStateOf(0) }
    var totalZipsCount by remember { mutableIntStateOf(0) }
    var activeZipName by remember { mutableStateOf("") }
    var activeItemName by remember { mutableStateOf("") }
    var progressFraction by remember { mutableFloatStateOf(0f) }

    var importedCount by remember { mutableIntStateOf(0) }
    var photoCount by remember { mutableIntStateOf(0) }
    var videoCount by remember { mutableIntStateOf(0) }
    var skippedCount by remember { mutableIntStateOf(0) }
    var failedCount by remember { mutableIntStateOf(0) }
    var finalReport by remember { mutableStateOf<TransferReport?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val list = uris.map { getZipDetails(context, it) }
            val existingUris = selectedZips.map { it.uri }.toSet()
            list.filter { it.uri !in existingUris }.forEach { selectedZips.add(it) }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(IndigoPrimary, CoralPrimary)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Logo PachaFoto",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "PachaFoto",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Transférez vos photos et vidéos sur votre téléphone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Enregistré dans Galerie > Pictures/PachaFoto",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            when (step) {
                TransferStep.SELECT -> {
                    item {
                        PickFilesCard(
                            selectedCount = selectedZips.size,
                            onPickFiles = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            }
                        )
                    }

                    if (selectedZips.isNotEmpty()) {
                        item {
                            val totalBytes = selectedZips.sumOf { it.sizeBytes }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${selectedZips.size} archive${if (selectedZips.size > 1) "s" else ""} (${formatFileSize(totalBytes)})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Tout effacer",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedZips.clear() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        items(selectedZips, key = { it.uri.toString() }) { zip ->
                            ZipFileItemCard(
                                zip = zip,
                                onRemove = { selectedZips.remove(zip) }
                            )
                        }

                        item {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (selectedZips.isNotEmpty()) {
                                        step = TransferStep.TRANSFERRING
                                        importedCount = 0
                                        photoCount = 0
                                        videoCount = 0
                                        skippedCount = 0
                                        failedCount = 0
                                        progressFraction = 0f
                                        currentZipIdx = 1
                                        totalZipsCount = selectedZips.size
                                        activeZipName = selectedZips.firstOrNull()?.name ?: ""

                                        onStartTransfer(
                                            selectedZips,
                                            { zIdx, totZips, zName, curItem, imp, ph, vid, skp, fail, prog ->
                                                currentZipIdx = zIdx
                                                totalZipsCount = totZips
                                                activeZipName = zName
                                                activeItemName = curItem
                                                importedCount = imp
                                                photoCount = ph
                                                videoCount = vid
                                                skippedCount = skp
                                                failedCount = fail
                                                progressFraction = prog
                                            },
                                            { report ->
                                                finalReport = report
                                                step = TransferStep.DONE
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("start_transfer_button"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = IndigoPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Commencer le transfert",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("add_more_files_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Ajouter d'autres fichiers ZIP")
                            }
                        }
                    } else {
                        item {
                            GuideTipsCard()
                        }
                    }
                }

                TransferStep.TRANSFERRING -> {
                    item {
                        TransferringProgressView(
                            currentZip = currentZipIdx,
                            totalZips = totalZipsCount,
                            activeZipName = activeZipName,
                            activeItemName = activeItemName,
                            progressFraction = progressFraction,
                            photos = photoCount,
                            videos = videoCount,
                            transferred = importedCount,
                            skipped = skippedCount,
                            failed = failedCount
                        )
                    }
                }

                TransferStep.DONE -> {
                    item {
                        val report = finalReport ?: TransferReport(importedCount, photoCount, videoCount, skippedCount, failedCount)
                        SuccessSummaryView(
                            report = report,
                            onNewTransfer = {
                                selectedZips.clear()
                                step = TransferStep.SELECT
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PickFilesCard(
    selectedCount: Int,
    onPickFiles: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onPickFiles() }
            .testTag("choose_files_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = if (selectedCount > 0) "Ajouter ou modifier des fichiers" else "Choisir les fichiers Takeout",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Sélectionnez une ou plusieurs archives .zip exportées depuis Google Takeout",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onPickFiles,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Choisir les fichiers Takeout",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ZipFileItemCard(
    zip: SelectedZip,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("zip_file_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = zip.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatFileSize(zip.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Supprimer l'archive",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun TransferringProgressView(
    currentZip: Int,
    totalZips: Int,
    activeZipName: String,
    activeItemName: String,
    progressFraction: Float,
    photos: Int,
    videos: Int,
    transferred: Int,
    skipped: Int,
    failed: Int
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        label = "progress"
    )

    val percent = (animatedProgress * 100).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Transfert en cours…",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Archive $currentZip sur $totalZips",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "$percent%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = IndigoPrimary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                if (activeItemName.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = activeItemName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Text(
            text = "Statistiques en direct",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                title = "Photos",
                value = formatNumber(photos),
                icon = Icons.Default.Photo,
                iconColor = CoralPrimary,
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                title = "Vidéos",
                value = formatNumber(videos),
                icon = Icons.Default.Movie,
                iconColor = IndigoPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                title = "Fichiers transférés",
                value = formatNumber(transferred),
                icon = Icons.Default.CheckCircle,
                iconColor = SuccessGreen,
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                title = "Fichiers ignorés",
                value = formatNumber(skipped),
                icon = Icons.Default.Info,
                iconColor = WarningAmber,
                subtitle = "Déjà présents",
                modifier = Modifier.weight(1f)
            )
        }

        if (failed > 0) {
            MetricStatCard(
                title = "Erreurs",
                value = formatNumber(failed),
                icon = Icons.Default.ErrorOutline,
                iconColor = ErrorRed,
                subtitle = "Non transférés",
                modifier = Modifier.fillMaxWidth()
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Veuillez garder l'application ouverte pendant l'extraction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SuccessSummaryView(
    report: TransferReport,
    onNewTransfer: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "Transfert terminé !",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Vos médias ont été extraits et ajoutés avec succès à votre Galerie.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryItemCard(
                count = "${formatNumber(report.photos)}",
                label = "photos",
                icon = Icons.Default.Photo,
                tint = CoralPrimary,
                modifier = Modifier.weight(1f)
            )
            SummaryItemCard(
                count = "${formatNumber(report.videos)}",
                label = "vidéos",
                icon = Icons.Default.Movie,
                tint = IndigoPrimary,
                modifier = Modifier.weight(1f)
            )
        }

        SummaryItemCard(
            count = "${formatNumber(report.imported)}",
            label = "fichiers transférés au total",
            icon = Icons.Default.CheckCircle,
            tint = SuccessGreen,
            modifier = Modifier.fillMaxWidth()
        )

        if (report.skipped > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = WarningAmber.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${formatNumber(report.skipped)} fichier${if (report.skipped > 1) "s" else ""} déjà présent${if (report.skipped > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (report.failed > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = ErrorRed.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${formatNumber(report.failed)} fichier${if (report.failed > 1) "s n'ont" else " n'a"} pas pu être transféré${if (report.failed > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onNewTransfer,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("new_transfer_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Nouveau transfert",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {}
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("open_gallery_button"),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Ouvrir la Galerie")
        }
    }
}

@Composable
fun MetricStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryItemCard(
    count: String,
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    text = count,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun GuideTipsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Comment ça fonctionne ?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            GuideStepRow(
                step = "1",
                text = "Téléchargez votre archive ZIP depuis Google Takeout (takeout.google.com)."
            )

            GuideStepRow(
                step = "2",
                text = "Sélectionnez le ou les fichiers .zip téléchargés dans PachaFoto."
            )

            GuideStepRow(
                step = "3",
                text = "PachaFoto extrait vos photos et vidéos et les classe directement dans votre Galerie sans nécessiter de mot de passe."
            )
        }
    }
}

@Composable
fun GuideStepRow(step: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(IndigoPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = IndigoPrimary
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
