package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.CommandType
import com.example.data.PhotoSource
import com.example.data.RemoteSession
import com.example.data.SharedPhoto
import com.example.data.UiState
import com.example.service.ImageCodec
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.OutlineSubtle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhotosScreen(
    activeSession: RemoteSession?,
    photosState: UiState<List<SharedPhoto>>,
    isBusy: Boolean,
    onSharePhotoBase64: (String, String) -> Unit,
    onPhotoError: (String) -> Unit,
    onCaptureRemoteSnapshot: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inspectedPhoto by remember { mutableStateOf<SharedPhoto?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val encoded = withContext(Dispatchers.IO) {
                    ImageCodec.encodeUriToBase64(context, uri)
                }
                if (encoded != null) {
                    onSharePhotoBase64("Gallery Photo", encoded)
                } else {
                    onPhotoError("Could not process selected photo")
                }
            }
        }
    }

    inspectedPhoto?.let { currentPhoto ->
        val fullBitmap by produceState<Bitmap?>(initialValue = null, currentPhoto.imageBase64) {
            value = withContext(Dispatchers.Default) {
                ImageCodec.decodeBase64ToBitmap(currentPhoto.imageBase64)
            }
        }
        Dialog(
            onDismissRequest = { inspectedPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianBg)
                    .systemBarsPadding(),
                color = ObsidianBg
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(currentPhoto.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "From ${currentPhoto.senderDeviceName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { inspectedPhoto = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close photo preview")
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val safeFullBitmap = fullBitmap?.takeIf { !it.isRecycled }
                        if (safeFullBitmap != null) {
                            Image(
                                bitmap = safeFullBitmap.asImageBitmap(),
                                contentDescription = currentPhoto.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "photo_actions") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Photos & Screen Snapshots", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (activeSession != null) {
                            "Connected to ${activeSession.hostDeviceName}. Share gallery photos or capture live screen snapshots."
                        } else {
                            "Connect to a trusted device in the Control tab to exchange photos and snapshots."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                } catch (_: Exception) {
                                    onPhotoError("Photo picker is unavailable on this device")
                                }
                            },
                            enabled = activeSession != null && !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("pick_photo_button")
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send Photo")
                        }
                        FilledTonalButton(
                            onClick = onCaptureRemoteSnapshot,
                            enabled = activeSession != null && !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("capture_snapshot_button")
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Snapshot")
                        }
                    }
                }
            }
        }

        when (photosState) {
            is UiState.Loading -> {
                item(span = { GridItemSpan(maxLineSpan) }, key = "photos_loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            is UiState.Error -> {
                item(span = { GridItemSpan(maxLineSpan) }, key = "photos_error") {
                    Text(photosState.message, color = CoralAlert)
                }
            }
            is UiState.Success -> {
                if (photosState.data.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "photos_empty") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    tint = CyanPulse,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text("No Shared Photos Yet", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Use Send Photo or Snapshot above to relay images between Phone 1 and Phone 2.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(photosState.data, key = { it.id }) { photo ->
                        SharedPhotoGridCard(
                            photo = photo,
                            onClick = { inspectedPhoto = photo }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedPhotoGridCard(
    photo: SharedPhoto,
    onClick: () -> Unit
) {
    val thumb by produceState<Bitmap?>(initialValue = null, photo.imageBase64) {
        value = withContext(Dispatchers.Default) {
            ImageCodec.decodeBase64ToBitmap(photo.imageBase64)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OutlineSubtle, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("shared_photo_${photo.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(ObsidianBg),
                contentAlignment = Alignment.Center
            ) {
                val safeThumb = thumb?.takeIf { !it.isRecycled }
                if (safeThumb != null) {
                    Image(
                        bitmap = safeThumb.asImageBitmap(),
                        contentDescription = photo.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
                Surface(
                    color = ObsidianBg.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (photo.source == PhotoSource.SCREEN_SNAPSHOT.name) "SNAPSHOT" else "PHOTO",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanPulse,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(photo.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(
                    photo.senderDeviceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
