package com.local.assistant.ui.gallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.assistant.gallery.GalleryIndex
import com.local.assistant.gallery.GalleryModel
import com.local.assistant.gallery.PhotoLibrary
import com.local.assistant.model.Transfer
import com.local.assistant.model.formatBytes
import com.local.assistant.ui.theme.AppColors
import java.text.NumberFormat

/**
 * Photos: the phone's photos, searchable by what's in them ("sunset", "birthday cake").
 *
 * Asks for photo access, then for the photo-search model, then indexes in the background while
 * the screen is open; search works on whatever is indexed so far.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<Pair<List<Long>, Int>?>(null) }
    var person by remember { mutableStateOf<PeopleTarget?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onVisible()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    // Locations and videos, once photos are shared; Android stops asking for a refused one itself.
    val followUpRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onPermissionResult() }
    LaunchedEffect(access) {
        val missing = viewModel.followUpPermissions
        if (access != PhotoLibrary.Access.NONE && missing.isNotEmpty()) followUpRequest.launch(missing)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = AppColors.Background,
            topBar = {
                TopAppBar(
                    title = { Text("Photos", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppColors.Background,
                        titleContentColor = AppColors.TextPrimary,
                        navigationIconContentColor = AppColors.TextPrimary,
                    ),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (access == PhotoLibrary.Access.NONE) {
                    AccessRequest(viewModel)
                    return@Box
                }
                val ids = when (val r = results) {
                    is PhotoResults.Newest -> r.ids
                    is PhotoResults.Found -> r.ids
                    else -> emptyList()
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            if (installed == null) {
                                ModelCard(viewModel)
                            } else {
                                SearchField(query, viewModel::setQuery)
                                IndexProgress(status)
                                // People only while nothing is typed: a search result is about the photos.
                                if (query.isBlank()) PeopleSection(viewModel, onOpen = { person = it })
                            }
                            if (access == PhotoLibrary.Access.SOME) PartialAccessNote(viewModel)
                            ResultLabel(results, status, viewModel.thumbnails)
                        }
                    }
                    items(ids, key = { it }) { id ->
                        PhotoThumb(id, viewModel.thumbnails, Modifier.aspectRatio(1f), onClick = { viewing = ids to ids.indexOf(id) })
                    }
                }
            }
        }

        viewing?.let { (ids, start) ->
            PhotoViewer(ids, start, viewModel.thumbnails, onClose = { viewing = null }, moments = (results as? PhotoResults.Found)?.moments.orEmpty())
        }
        person?.let { PersonSheet(it, viewModel, onDismiss = { person = null }) }
    }
}

@Composable
private fun AccessRequest(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        viewModel.onPermissionResult()
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Search your photos by what's in them", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
        Text(
            "Type \"sunset\" or \"birthday cake\" and see those photos. The app looks at your photos on this phone to make that " +
                "possible. It never changes, moves or uploads them.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = { request.launch(viewModel.permissions) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Allow access to photos") }
        // Refused twice, Android stops asking; only its settings can change it then.
        if (asked) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Open app settings") }
        }
    }
}

@Composable
private fun ModelCard(viewModel: GalleryViewModel) {
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val error by viewModel.downloadError.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.SurfaceMuted)
            .padding(16.dp),
    ) {
        Text("Turn on photo search", style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        Text(
            "Searching photos by what's in them needs ${GalleryModel.DISPLAY_NAME}, a ${formatBytes(GalleryModel.SIZE_BYTES)} " +
                "download from Hugging Face. It runs on this phone; your photos never leave it.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        val current = transfer
        if (current != null) {
            DownloadProgress(current)
            OutlinedButton(onClick = viewModel::cancelDownload, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = viewModel::download,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                modifier = Modifier.padding(top = 12.dp),
            ) { Text("Download") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Danger, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun DownloadProgress(transfer: Transfer) {
    LinearProgressIndicator(
        progress = { transfer.fraction },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        color = AppColors.Accent,
        trackColor = AppColors.Border,
    )
    Text(
        "${formatBytes(transfer.bytesDone)} of ${formatBytes(transfer.bytesTotal)}",
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search photos: sunset, dog, birthday cake…") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun IndexProgress(status: GalleryIndex.Status) {
    if (status.total == 0 || status.remaining == 0) return
    val numbers = NumberFormat.getIntegerInstance()
    LinearProgressIndicator(
        progress = { status.indexed.toFloat() / status.total },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        color = AppColors.Accent,
        trackColor = AppColors.Border,
    )
    Text(
        text = if (status.running) {
            "Getting photos ready for search: ${numbers.format(status.indexed)} of ${numbers.format(status.total)}. " +
                "Search already covers those."
        } else {
            "${numbers.format(status.remaining)} photos still to get ready. That carries on while this screen is open, " +
                "and overnight while charging."
        },
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun PartialAccessNote(viewModel: GalleryViewModel) {
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onPermissionResult() }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Only the photos you picked are shared with the app.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { request.launch(viewModel.permissions) }) { Text("Change") }
    }
}

@Composable
private fun ResultLabel(results: PhotoResults, status: GalleryIndex.Status, thumbnails: com.local.assistant.gallery.PhotoThumbnails) {
    val videos by produceState(0, results) { value = (results as? PhotoResults.Found)?.let { thumbnails.videoCount(it.ids) } ?: 0 }
    val text = when (results) {
        PhotoResults.Loading -> null
        is PhotoResults.Newest -> if (results.ids.isEmpty()) "No photos on this phone yet." else "Recent"
        is PhotoResults.Found -> when {
            results.ids.isNotEmpty() -> itemCount(results.ids.size, videos)
            status.indexed == 0 -> "No photos are ready for search yet."
            else -> "No photos match \"${results.query}\"."
        }
        is PhotoResults.CannotSearch -> "Photo search isn't available: the model couldn't be loaded."
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (results == PhotoResults.Loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.TextSecondary)
        }
        text?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary) }
    }
}
