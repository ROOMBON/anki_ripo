package com.example.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(viewModel: AppViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val isZipLoaded = !settings?.zipFileUri.isNullOrEmpty()
    val isRepoJsonLoaded = !settings?.repoJsonPath.isNullOrEmpty()
    
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val exportedZipFile by viewModel.exportedZipFile.collectAsStateWithLifecycle()
    val partialFileToImport by viewModel.partialImportFile.collectAsStateWithLifecycle()
    val ankiImportResult by viewModel.ankiImportResult.collectAsStateWithLifecycle()

    // Handle exported file sharing
    LaunchedEffect(exportedZipFile) {
        val file = exportedZipFile
        if (file != null) {
            shareZipFile(context, file)
            viewModel.clearExportedState()
        }
    }

    // Force system layout to RightToLeft for a completely natural Hebrew experience
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            bottomBar = {
                if (isZipLoaded && isRepoJsonLoaded) {
                    NavigationBar(
                        tonalElevation = 8.dp,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Filled.Folder, contentDescription = "מאגר") },
                            label = { Text("מאגר שאלות") },
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = "מסלולים") },
                            label = { Text("הספקים יומיים") },
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "הגדרות") },
                            label = { Text("הגדרות") },
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
            ) {
                if (!isZipLoaded || !isRepoJsonLoaded) {
                    WelcomeConfigurationScreen(viewModel, settings)
                } else {
                    when (selectedTab) {
                        0 -> RepositoryScreen(viewModel)
                        1 -> DailyTracksScreen(viewModel)
                        2 -> ConfigurationTabScreen(viewModel, settings)
                    }
                }

                // Global dialog for progress loading spinner
                importProgress?.let { (fraction, description) ->
                    Dialog(onDismissRequest = {}) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            elevation = CardDefaults.cardElevation(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(52.dp),
                                    strokeWidth = 4.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = description,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = fraction,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }

                // Global overlay for Partial Import Editor
                if (partialFileToImport != null) {
                    PartialImportSheet(viewModel = viewModel)
                }

                // Global dialog for direct Anki import results
                ankiImportResult?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { viewModel.clearAnkiImportResult() },
                        title = { Text("תוצאת ייבוא לאנקידראויד", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        text = { Text(msg, fontSize = 14.sp) },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.clearAnkiImportResult() },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("אישור")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeConfigurationScreen(viewModel: AppViewModel, settings: AppSettings?) {
    val context = LocalContext.current
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                // Request persistable permission so it doesn't expire
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                val fileName = getFileNameFromUri(context, uri) ?: "מאגר.zip"
                viewModel.setZipFile(uri, fileName)
            }
        }
    )

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        val jsonString = ZipHelper.decodeBytesToHebrewString(bytes).trimStart('\uFEFF')
                        viewModel.setRepoJson(jsonString)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "שגיאה בקריאת הקובץ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Archive,
                        contentDescription = "Archive",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "עוזר מאגר השאלות לאנקי",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "כדי להתחיל, עליך לטעון את קובץ המאגר הראשי (קובץ ZIP או 7Z המכיל את השאלות ואת מבנה התיקיות repo_struct.json)",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-7z-compressed", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "Upload")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("בחר קובץ ZIP / 7Z מהמכשיר", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.Code, contentDescription = "JSON")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("בחר קובץ repo_struct.json בנפרד", fontWeight = FontWeight.Bold)
                }

                if (settings?.zipFileUri != null && settings.repoJsonPath == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "⚠️ נמצא קובץ ZIP אך לא חולץ ממנו קובץ repo_struct.json. וודא שבשורש ה-ZIP קיים קובץ המבנה, או לחץ על הכפתור למעלה ובחר את קובץ repo_struct.json מהמכשיר בנפרד.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RepositoryScreen(viewModel: AppViewModel) {
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val contents by viewModel.currentDirContents.collectAsStateWithLifecycle()
    val selectedFiles by viewModel.selectedFilePaths.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val ankiPermission = com.example.data.AnkiDroidHelper.getPermissionName(context)
    val ankiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                viewModel.executeAnkiDroidImport()
            } else {
                Toast.makeText(context, "נדרשת הרשאת גישה לאנקידראויד כדי לבצע ייבוא ישיר.", Toast.LENGTH_LONG).show()
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & Filter header
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.performSearch(it) },
            placeholder = { Text("חיפש שאלות (תומך בקיצורים וגימטריא...)") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "חיפוש") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.performSearch("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "נקה")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp)
        )

        AnimatedVisibility(visible = searchQuery.isEmpty() && currentPath.isNotEmpty()) {
            val scrollState = rememberScrollState()
            LaunchedEffect(currentPath.size) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                currentPath.forEachIndexed { idx, folderName ->
                    val isLast = idx == currentPath.size - 1
                    Text(
                        text = folderName,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { viewModel.navigateTo(idx) }
                            .padding(vertical = 4.dp, horizontal = 6.dp)
                    )
                    if (!isLast) {
                        Text(
                            text = "/",
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        // Contents Viewer
        Box(modifier = Modifier.weight(1f)) {
            if (searchQuery.isNotEmpty()) {
                // Display search matching matches
                when (val state = searchResults) {
                    is SearchResultsState.Searching -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    is SearchResultsState.Success -> {
                        if (state.results.isEmpty()) {
                            EmptyPlaceholderState("לא נמצאו קבצים תואמים לחיפוש")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.results) { (path, node) ->
                                    val isFileChecked = selectedFiles.contains(path)
                                    FileItemRow(
                                        node = node,
                                        filePath = path,
                                        isChecked = isFileChecked,
                                        onCheckedChange = { viewModel.toggleFileSelected(path) },
                                        onPartialClick = { viewModel.openPartialImport(path) }
                                    )
                                }
                            }
                        }
                    }
                    else -> {}
                }
            } else {
                // Standard folder explorer
                if (contents.isEmpty()) {
                    EmptyPlaceholderState("תיקייה זו ריקה")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            // Sub-header for Directory Actions
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(onClick = { viewModel.selectAllInCurrentFolder() }) {
                                    Icon(Icons.Filled.LibraryAdd, contentDescription = "Select All")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("סמן הכל")
                                }
                                TextButton(onClick = { viewModel.deselectAllInCurrentFolder() }) {
                                    Icon(Icons.Filled.LibraryBooks, contentDescription = "Clear All")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("בטל סימון")
                                }
                            }
                        }

                        items(contents) { node ->
                            val currentPathStr = currentPath.joinToString("/")
                            val childPathStr = "$currentPathStr/${node.name}"

                            if (node.isDirectory) {
                                FolderItemRow(
                                    node = node,
                                    onClick = { viewModel.enterFolder(node.name) },
                                    onFolderCheckToggle = { viewModel.toggleFolderSelected(node, currentPathStr) }
                                )
                            } else {
                                val isFileChecked = selectedFiles.contains(childPathStr)
                                FileItemRow(
                                    node = node,
                                    filePath = childPathStr,
                                    isChecked = isFileChecked,
                                    onCheckedChange = { viewModel.toggleFileSelected(childPathStr) },
                                    onPartialClick = { viewModel.openPartialImport(childPathStr) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Import FAB / Trigger footer
        if (selectedFiles.isNotEmpty()) {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "סומנו ${selectedFiles.size} קבצים",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "בחר שיטת ייבוא לאנקידרואיד:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Direct import via AnkiDroid API
                        Button(
                            onClick = {
                                val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    ankiPermission
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (isGranted) {
                                    viewModel.executeAnkiDroidImport()
                                } else {
                                    ankiPermissionLauncher.launch(ankiPermission)
                                }
                            },
                            modifier = Modifier.weight(1.0f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Bolt, contentDescription = "ייבוא ישיר API")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ייבוא ישיר API", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // Share ZIP file
                        Button(
                            onClick = { viewModel.executeImport() },
                            modifier = Modifier.weight(1.0f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "שיתוף ZIP")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ייצוא ושיתוף", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderItemRow(node: RepoNode, onClick: () -> Unit, onFolderCheckToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = "Folder",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name.replace("%", " "),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${node.children.size} פריטים בפנים",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { onFolderCheckToggle() }) {
                Icon(
                    Icons.Filled.LibraryAddCheck,
                    contentDescription = "בחר הכל בתיקייה",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun FileItemRow(
    node: RepoNode,
    filePath: String,
    isChecked: Boolean,
    onCheckedChange: () -> Unit,
    onPartialClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onCheckedChange() }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name.substringBeforeLast(".txt").replace("_", " "),
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = filePath.substringAfter("מאגר השאלות/").substringBeforeLast("/"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { onPartialClick() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Filled.List, contentDescription = "ייבוא חלקי", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("ייבוא חלקי", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PartialImportSheet(viewModel: AppViewModel) {
    val qList by viewModel.partialQuestions.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedPartialKeys.collectAsStateWithLifecycle()
    val fileName = viewModel.partialImportFile.value?.substringAfterLast("/") ?: ""

    val context = LocalContext.current
    val ankiPermission = com.example.data.AnkiDroidHelper.getPermissionName(context)
    val ankiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                viewModel.executeAnkiDroidPartialImport()
            } else {
                Toast.makeText(context, "נדרשת הרשאת גישה לאנקידראויד כדי לבצע ייבוא ישיר.", Toast.LENGTH_LONG).show()
            }
        }
    )

    Dialog(onDismissRequest = { viewModel.closePartialImport() }) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ייבוא חלקי: ${fileName.replace("_", " ").substringBeforeLast(".txt")}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${qList.size} שאלות נמצאו בקובץ",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(onClick = { viewModel.closePartialImport() }) {
                        Icon(Icons.Filled.Close, contentDescription = "סגור")
                    }
                }

                // Control panel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.selectAllPartial() }) {
                        Text("בחר הכל")
                    }
                    TextButton(onClick = { viewModel.deselectAllPartial() }) {
                        Text("בטל הכל")
                    }
                    Text(
                        text = "נבחרו ${selectedKeys.size}",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Divider()

                // Questions Lazy List
                Box(modifier = Modifier.weight(1f)) {
                    if (qList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("קורא שאלות...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(qList) { q ->
                                val isChecked = selectedKeys.contains(q.key)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .clickable { viewModel.toggleQuestionSelected(q.key) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (q.isImported) {
                                            PaleGreen.copy(alpha = 0.5f)
                                        } else if (isChecked) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { viewModel.toggleQuestionSelected(q.key) }
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = q.front,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = q.back,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (q.isImported) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "✓ יובא בעבר בסשן זה",
                                                    color = ActiveGreen,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Divider()

                // Export FAB footer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Direct import via AnkiDroid API
                        Button(
                            onClick = {
                                val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    ankiPermission
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (isGranted) {
                                    viewModel.executeAnkiDroidPartialImport()
                                } else {
                                    ankiPermissionLauncher.launch(ankiPermission)
                                }
                            },
                            enabled = selectedKeys.isNotEmpty(),
                            modifier = Modifier.weight(1.0f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Bolt, contentDescription = "ייבוא ישיר API")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ייבוא ישיר API", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        // Export ZIP and share
                        Button(
                            onClick = { viewModel.executePartialImport() },
                            enabled = selectedKeys.isNotEmpty(),
                            modifier = Modifier.weight(1.0f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "שיתוף ZIP")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ייצוא ושיתוף", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DailyTracksScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val tracks by viewModel.tracksFlow.collectAsStateWithLifecycle()
    val repoTree by viewModel.repoTree.collectAsStateWithLifecycle()
    
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "מסלולי לימוד יומיים 🎯",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            Button(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Track")
                Spacer(modifier = Modifier.width(4.dp))
                Text("הוסף מסלול")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "טרם הגדרת מסלולים יומיים. לחץ על 'הוסף מסלול' כדי לצעוד בקצב לימוד אישי!",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(tracks) { track ->
                    // Calculate estimated total files in track folder
                    val filesList = if (repoTree != null) {
                        RepoParser.findFilesUnderFolder(repoTree!!, track.rootFolder)
                    } else {
                        emptyList()
                    }
                    val totalFiles = filesList.size
                    
                    val progressValue = if (totalFiles > 0) {
                        track.currentFileIndex.toFloat() / totalFiles
                    } else {
                        0.0f
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = track.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        text = track.rootFolder.substringAfter("מאגר השאלות/"),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row {
                                    IconButton(
                                        onClick = { viewModel.deleteTrack(track.id) }
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Current book title
                            val currentFileTitle = filesList.getOrNull(track.currentFileIndex)
                                ?.substringAfterLast("/")
                                ?.substringBeforeLast(".txt")
                                ?.replace("_", " ") ?: "בוצע כולו!"
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "ספר נוכחי: $currentFileTitle",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${track.currentFileIndex} מתוך $totalFiles קבצים",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Button(
                                    onClick = {
                                        // Open partial import for currently active file or folder index
                                        val activeFile = filesList.getOrNull(track.currentFileIndex)
                                        if (activeFile != null) {
                                            viewModel.openPartialImport(activeFile)
                                        } else {
                                            Toast.makeText(context, "הגעת לסוף המסלול!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = track.currentFileIndex < totalFiles,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Filled.AutoFixHigh, contentDescription = "Active File")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("למד קובץ נוכחי")
                                }

                                Button(
                                    onClick = {
                                        viewModel.advanceTrack(track)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                    shape = RoundedCornerShape(8.dp),
                                    enabled = track.currentFileIndex < totalFiles
                                ) {
                                    Icon(Icons.Filled.SkipNext, contentDescription = "Advance")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("סמן כהושלם")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog && repoTree != null) {
        AddTrackDialog(
            repoTree = repoTree!!,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, folder, file, count, partial ->
                viewModel.addNewTrack(name, folder, file, count, partial)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun AddTrackDialog(
    repoTree: RepoNode,
    onDismiss: () -> Unit,
    onConfirm: (name: String, folder: String, startFile: String, count: Int, isPartial: Boolean) -> Unit
) {
    var trackName by remember { mutableStateOf("") }
    var stepCount by remember { mutableStateOf(1) }
    var isPartial by remember { mutableStateOf(true) }

    // Flatten folders to select from
    val folderList = remember(repoTree) {
        val list = mutableListOf<String>()
        fun recurse(node: RepoNode, path: String) {
            val nPath = if (path.isEmpty()) node.name else "$path/${node.name}"
            if (node.isDirectory) {
                if (node.children.any { it.isFile }) {
                    list.add(nPath)
                }
                for (child in node.children) {
                    recurse(child, nPath)
                }
            }
        }
        recurse(repoTree, "")
        list
    }

    var selectedFolder by remember { mutableStateOf(folderList.firstOrNull() ?: "") }
    
    val filesList = remember(selectedFolder) {
        RepoParser.findFilesUnderFolder(repoTree, selectedFolder)
    }
    var selectedFile by remember { mutableStateOf(filesList.firstOrNull() ?: "") }
    
    LaunchedEffect(selectedFolder) {
        selectedFile = filesList.firstOrNull() ?: ""
    }

    Dialog(onDismissRequest = { onDismiss() }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "הוספת מסלול לימוד חדש 📚",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = trackName,
                    onValueChange = { trackName = it },
                    label = { Text("שם המסלול (לדוגמה: דף היומי, משנ''ב)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("בחר נושא/תיקיית יעד:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                
                // Simplified Scrollable list for folder selection or standard dropdown mimics
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(folderList) { f ->
                            val isSel = f == selectedFolder
                            Text(
                                text = f.substringAfter("מאגר השאלות/"),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFolder = f }
                                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .padding(8.dp),
                                color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("בחר קובץ התחלה:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filesList) { file ->
                            val isSel = file == selectedFile
                            Text(
                                text = file.substringAfterLast("/").substringBeforeLast(".txt").replace("_", " "),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFile = file }
                                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .padding(8.dp),
                                color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isPartial, onCheckedChange = { isPartial = it })
                    Text("מאפשר ייבוא חלקי (רשומות בודדות בכל יום)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onDismiss() }) {
                        Text("ביטול")
                    }
                    Button(
                        onClick = {
                            if (trackName.isNotBlank() && selectedFolder.isNotEmpty() && selectedFile.isNotEmpty()) {
                                onConfirm(trackName, selectedFolder, selectedFile, stepCount, isPartial)
                            }
                        },
                        enabled = trackName.isNotBlank() && selectedFolder.isNotEmpty() && selectedFile.isNotEmpty()
                    ) {
                        Text("שמור")
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigurationTabScreen(viewModel: AppViewModel, settings: AppSettings?) {
    val schedules by viewModel.schedulesFlow.collectAsStateWithLifecycle()
    val zipFileName = settings?.zipFileName ?: "לא נטען קובץ"
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val fileName = getFileNameFromUri(context, uri) ?: "מאגר.zip"
                viewModel.setZipFile(uri, fileName)
            }
        }
    )

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        val jsonString = ZipHelper.decodeBytesToHebrewString(bytes).trimStart('\uFEFF')
                        viewModel.setRepoJson(jsonString)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "שגיאה בקריאת הקובץ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "הגדרות האפליקציה ⚙️",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Files Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("מקור המאגר הנוכחי", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "קובץ מאגר: $zipFileName", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (settings?.repoJsonPath != null) "✓ קובץ המבנה repo_struct.json חולץ בהצלחה" else "✗ קובץ המבנה טרם נטען",
                            fontSize = 11.sp,
                            color = if (settings?.repoJsonPath != null) ActiveGreen else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-7z-compressed", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Filled.Sync, contentDescription = "קובץ חדש", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "טען קובץ repo_struct.json בנפרד",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Filled.Code, contentDescription = "קובץ מבנה בנפרד", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Notifications configuration
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("התראות ותזכורות לימוד", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    
                    IconButton(
                        onClick = {
                            val timePicker = TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    viewModel.addSchedule(hour, minute)
                                },
                                12, 0, true
                            )
                            timePicker.show()
                        }
                    ) {
                        Icon(Icons.Filled.AddAlarm, contentDescription = "Add Alarm", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (schedules.isEmpty()) {
                    Text(
                        "טרם הוגדרו תזכורות למהלך היום.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                    ) {
                        items(schedules) { schedule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val timeText = String.format("%02d:%02d", schedule.hour, schedule.minute)
                                Text(
                                    text = "תזכורת בשעה: $timeText",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = schedule.isEnabled,
                                        onCheckedChange = { viewModel.toggleScheduleEnabled(schedule) }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(onClick = { viewModel.deleteSchedule(schedule.id) }) {
                                        Icon(Icons.Filled.DeleteOutline, contentDescription = "מחק", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Advanced / Support
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("כלים מתקדמים", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.clearImportHistory()
                        Toast.makeText(context, "היסטוריית הכרטיסים שיובאו אופסה בהצלחה!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reset History")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("אפס היסטוריית כרטיסים שכבר יובאו")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.clearAllSelections()
                        viewModel.clearConfiguration()
                        Toast.makeText(context, "כל הגדרות האפליקציה נמחקו", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Clear Config")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("אפס את הגדרות האפליקציה לחלוטין")
                }
            }
        }
    }
}

// ----------------------------------------------------
// UI Helpers
// ----------------------------------------------------

@Composable
fun EmptyPlaceholderState(description: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.FolderOpen,
            contentDescription = "Empty",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = description,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) cursor.getString(nameIndex) else null
        } else null
    }
}

private fun shareZipFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val chooserIntent = Intent.createChooser(shareIntent, "ייבוא לאנקידרואיד")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "שגיאה בשיתוף הקובץ: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
