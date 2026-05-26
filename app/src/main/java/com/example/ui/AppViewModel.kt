package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.*
import com.example.worker.NotificationAlarmHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SearchResultsState {
    object Idle : SearchResultsState
    object Searching : SearchResultsState
    data class Success(val results: List<Pair<String, RepoNode>>) : SearchResultsState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    
    private val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "anki_qa_database"
    ).fallbackToDestructiveMigration().build()
    
    private val dao = database.appDao()

    // ----------------------------------------------------
    // UI State variables
    // ----------------------------------------------------
    
    val settingsFlow: StateFlow<AppSettings?> = dao.getSettingsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tracksFlow: StateFlow<List<DailyTrack>> = dao.getAllTracksFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val schedulesFlow: StateFlow<List<NotificationSchedule>> = dao.getAllSchedulesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val importedKeysFlow: StateFlow<List<String>> = dao.getAllImportedKeysFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Parsing tree state
    private val _repoTree = MutableStateFlow<RepoNode?>(null)
    val repoTree: StateFlow<RepoNode?> = _repoTree.asStateFlow()

    // Current navigation folder path
    private val _currentPath = MutableStateFlow<List<String>>(emptyList())
    val currentPath: StateFlow<List<String>> = _currentPath.asStateFlow()

    // Current directory contents
    private val _currentDirContents = MutableStateFlow<List<RepoNode>>(emptyList())
    val currentDirContents: StateFlow<List<RepoNode>> = _currentDirContents.asStateFlow()

    // Selected file paths inside the Repo
    private val _selectedFilePaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedFilePaths: StateFlow<Set<String>> = _selectedFilePaths.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<SearchResultsState>(SearchResultsState.Idle)
    val searchResults: StateFlow<SearchResultsState> = _searchResults.asStateFlow()

    // Partial import state
    private val _partialImportFile = MutableStateFlow<String?>(null)
    val partialImportFile: StateFlow<String?> = _partialImportFile.asStateFlow()

    private val _partialQuestions = MutableStateFlow<List<Question>>(emptyList())
    val partialQuestions: StateFlow<List<Question>> = _partialQuestions.asStateFlow()

    private val _selectedPartialKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedPartialKeys: StateFlow<Set<String>> = _selectedPartialKeys.asStateFlow()

    // Loader progress
    private val _importProgress = MutableStateFlow<Pair<Float, String>?>(null)
    val importProgress: StateFlow<Pair<Float, String>?> = _importProgress.asStateFlow()

    private val _exportedZipFile = MutableStateFlow<File?>(null)
    val exportedZipFile: StateFlow<File?> = _exportedZipFile.asStateFlow()

    // ----------------------------------------------------
    // Initialization
    // ----------------------------------------------------
    init {
        viewModelScope.launch {
            var lastRepoPath: String? = null
            var lastRepoFileModifiedTime: Long = 0
            settingsFlow.collectLatest { settings ->
                val path = settings?.repoJsonPath
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    val currentModified = if (file.exists()) file.lastModified() else 0
                    if (path != lastRepoPath || currentModified != lastRepoFileModifiedTime) {
                        lastRepoPath = path
                        lastRepoFileModifiedTime = currentModified
                        
                        val root = withContext(Dispatchers.Default) {
                            try {
                                if (file.exists()) {
                                    val jsonContent = file.readText(Charsets.UTF_8)
                                    RepoParser.parseJson(jsonContent)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                        _repoTree.value = root
                        _currentPath.value = listOf(root?.name ?: "מאגר השאלות")
                        updateDirContents()
                    }
                } else {
                    lastRepoPath = null
                    lastRepoFileModifiedTime = 0
                    _repoTree.value = null
                    _currentDirContents.value = emptyList()
                    _currentPath.value = emptyList()
                }
            }
        }
    }

    // ----------------------------------------------------
    // Configuration & Settings Actions
    // ----------------------------------------------------
    
    fun setZipFile(uri: Uri, fileName: String) {
        viewModelScope.launch {
            val currentSettings = dao.getSettings() ?: AppSettings()
            
            _importProgress.value = 0.1f to "בודק קובץ ZIP..."
            val repoJson = withContext(Dispatchers.IO) {
                ZipHelper.findAndExtractRepoStruct(context, uri)
            }

            val savedPath = if (repoJson != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val file = File(context.filesDir, "repo_tree.json")
                        file.writeText(repoJson, Charsets.UTF_8)
                        file.absolutePath
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            } else {
                currentSettings.repoJsonPath
            }
            
            val updatedSettings = currentSettings.copy(
                zipFileUri = uri.toString(),
                zipFileName = fileName,
                repoJsonPath = savedPath
            )
            
            dao.saveSettings(updatedSettings)
            _importProgress.value = null
        }
    }

    fun setRepoJson(jsonString: String) {
        viewModelScope.launch {
            val currentSettings = dao.getSettings() ?: AppSettings()
            val savedPath = withContext(Dispatchers.IO) {
                try {
                    val file = File(context.filesDir, "repo_tree.json")
                    file.writeText(jsonString, Charsets.UTF_8)
                    file.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            val updatedSettings = currentSettings.copy(
                repoJsonPath = savedPath ?: currentSettings.repoJsonPath
            )
            dao.saveSettings(updatedSettings)
        }
    }

    fun clearConfiguration() {
        viewModelScope.launch {
            dao.saveSettings(AppSettings())
            try {
                val file = File(context.filesDir, "repo_tree.json")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _repoTree.value = null
            _currentPath.value = emptyList()
            _currentDirContents.value = emptyList()
            _selectedFilePaths.value = emptySet()
            _searchQuery.value = ""
        }
    }

    // ----------------------------------------------------
    // Folder Explorer Actions
    // ----------------------------------------------------

    fun navigateTo(index: Int) {
        val path = _currentPath.value
        if (index >= 0 && index < path.size) {
            _currentPath.value = path.take(index + 1)
            updateDirContents()
        }
    }

    fun enterFolder(folderName: String) {
        _currentPath.value = _currentPath.value + folderName
        updateDirContents()
    }

    fun goBackFolder(): Boolean {
        val path = _currentPath.value
        return if (path.size > 1) {
            _currentPath.value = path.dropLast(1)
            updateDirContents()
            true
        } else {
            false
        }
    }

    private fun updateDirContents() {
        val root = _repoTree.value ?: return
        val pathList = _currentPath.value
        if (pathList.isEmpty()) {
            _currentDirContents.value = emptyList()
            return
        }

        var current: RepoNode = root
        // Skip comparing first if root matches
        val startIndex = if (pathList[0].equals(root.name, ignoreCase = true)) 1 else 0

        for (i in startIndex until pathList.size) {
            val segment = pathList[i]
            val nextNode = current.children.find { it.name.equals(segment, ignoreCase = true) }
            if (nextNode != null) {
                current = nextNode
            } else {
                break
            }
        }
        _currentDirContents.value = current.children
    }

    // ----------------------------------------------------
    // Selection Management
    // ----------------------------------------------------

    fun toggleFileSelected(filePath: String) {
        val currentSet = _selectedFilePaths.value.toMutableSet()
        if (currentSet.contains(filePath)) {
            currentSet.remove(filePath)
        } else {
            currentSet.add(filePath)
        }
        _selectedFilePaths.value = currentSet
    }

    fun toggleFolderSelected(folderNode: RepoNode, parentPathString: String) {
        val root = _repoTree.value ?: return
        val currentSet = _selectedFilePaths.value.toMutableSet()

        val fullFolderPath = if (parentPathString.isEmpty()) folderNode.name else "$parentPathString/${folderNode.name}"
        val filesInFolder = RepoParser.findFilesUnderFolder(root, fullFolderPath)

        val allAreSelected = filesInFolder.all { currentSet.contains(it) }
        if (allAreSelected) {
            filesInFolder.forEach { currentSet.remove(it) }
        } else {
            filesInFolder.forEach { currentSet.add(it) }
        }
        _selectedFilePaths.value = currentSet
    }

    fun selectAllInCurrentFolder() {
        val root = _repoTree.value ?: return
        val folderPathString = _currentPath.value.joinToString("/")
        val filesInFolder = RepoParser.findFilesUnderFolder(root, folderPathString)
        
        val currentSet = _selectedFilePaths.value.toMutableSet()
        currentSet.addAll(filesInFolder)
        _selectedFilePaths.value = currentSet
    }

    fun deselectAllInCurrentFolder() {
        val root = _repoTree.value ?: return
        val folderPathString = _currentPath.value.joinToString("/")
        val filesInFolder = RepoParser.findFilesUnderFolder(root, folderPathString)
        
        val currentSet = _selectedFilePaths.value.toMutableSet()
        currentSet.removeAll(filesInFolder)
        _selectedFilePaths.value = currentSet
    }

    fun clearAllSelections() {
        _selectedFilePaths.value = emptySet()
    }

    // ----------------------------------------------------
    // Search Implementation
    // ----------------------------------------------------

    private var searchJob: kotlinx.coroutines.Job? = null

    fun performSearch(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        val root = _repoTree.value
        if (query.isBlank() || root == null) {
            _searchResults.value = SearchResultsState.Idle
            return
        }

        _searchResults.value = SearchResultsState.Searching
        searchJob = viewModelScope.launch {
            // Wait 200ms before running the search process (debouncing)
            kotlinx.coroutines.delay(200)
            
            val results = withContext(Dispatchers.Default) {
                val matched = mutableListOf<Pair<String, RepoNode>>()
                
                fun searchNode(node: RepoNode, currentPath: String) {
                    if (searchJob?.isActive != true) return
                    val nodePath = if (currentPath.isEmpty()) node.name else "$currentPath/${node.name}"
                    
                    if (node.isFile) {
                        // Replicate Python search with custom gematria-lookup scoped to the second-to-last segment
                        val formattedSearchPath = nodePath.replace("/", "::")
                        if (HebrewSearchEngine.orderedTermsMatch(formattedSearchPath, query)) {
                            matched.add(nodePath to node)
                        }
                    } else {
                        for (child in node.children) {
                            if (searchJob?.isActive != true) return
                            searchNode(child, nodePath)
                        }
                    }
                }
                
                searchNode(root, "")
                matched
            }
            
            if (searchJob?.isActive == true) {
                _searchResults.value = SearchResultsState.Success(results)
            }
        }
    }

    // ----------------------------------------------------
    // Partial Question Editor Implementation
    // ----------------------------------------------------

    fun openPartialImport(filePath: String) {
        _partialImportFile.value = filePath
        val uriStr = settingsFlow.value?.zipFileUri ?: return
        val uri = Uri.parse(uriStr)

        viewModelScope.launch {
            val questions = withContext(Dispatchers.IO) {
                ZipHelper.readAndParseTxtFile(context, uri, filePath, importedKeysFlow.value.toSet())
            }
            _partialQuestions.value = questions
            // Only select not-yet-imported questions by default
            _selectedPartialKeys.value = questions.filter { !it.isImported }.map { it.key }.toSet()
        }
    }

    fun closePartialImport() {
        _partialImportFile.value = null
        _partialQuestions.value = emptyList()
        _selectedPartialKeys.value = emptySet()
    }

    fun toggleQuestionSelected(key: String) {
        val current = _selectedPartialKeys.value.toMutableSet()
        if (current.contains(key)) {
            current.remove(key)
        } else {
            current.add(key)
        }
        _selectedPartialKeys.value = current
    }

    fun selectAllPartial() {
        _selectedPartialKeys.value = _partialQuestions.value.map { it.key }.toSet()
    }

    fun deselectAllPartial() {
        _selectedPartialKeys.value = emptySet()
    }

    // ----------------------------------------------------
    // Tracks Logic
    // ----------------------------------------------------

    fun addNewTrack(name: String, rootFolder: String, startFile: String, advanceBy: Int, isPartial: Boolean) {
        viewModelScope.launch {
            // Find currentFileIndex inside this folder
            val filesList = RepoParser.findFilesUnderFolder(_repoTree.value ?: return@launch, rootFolder)
            val idx = filesList.indexOf(startFile).coerceAtLeast(0)

            val track = DailyTrack(
                name = name,
                rootFolder = rootFolder,
                startFile = startFile,
                currentFileIndex = idx,
                advanceBy = advanceBy,
                isPartial = isPartial
            )
            dao.insertTrack(track)
        }
    }

    fun deleteTrack(id: Int) {
        viewModelScope.launch {
            dao.deleteTrack(id)
        }
    }

    fun advanceTrack(track: DailyTrack) {
        viewModelScope.launch {
            val filesList = RepoParser.findFilesUnderFolder(_repoTree.value ?: return@launch, track.rootFolder)
            val nextIdx = (track.currentFileIndex + track.advanceBy).coerceAtMost(filesList.size)
            dao.updateTrack(track.copy(currentFileIndex = nextIdx))
        }
    }

    // ----------------------------------------------------
    // Schedules Actions
    // ----------------------------------------------------

    fun addSchedule(hour: Int, minute: Int) {
        viewModelScope.launch {
            val sched = NotificationSchedule(hour = hour, minute = minute)
            val id = dao.insertSchedule(sched)
            NotificationAlarmHelper.scheduleAlarm(context, sched.copy(id = id.toInt()))
        }
    }

    fun toggleScheduleEnabled(schedule: NotificationSchedule) {
        viewModelScope.launch {
            val updated = schedule.copy(isEnabled = !schedule.isEnabled)
            dao.updateSchedule(updated)
            if (updated.isEnabled) {
                NotificationAlarmHelper.scheduleAlarm(context, updated)
            } else {
                NotificationAlarmHelper.cancelAlarm(context, updated.id)
            }
        }
    }

    fun deleteSchedule(id: Int) {
        viewModelScope.launch {
            NotificationAlarmHelper.cancelAlarm(context, id)
            dao.deleteSchedule(id)
        }
    }

    fun clearImportHistory() {
        viewModelScope.launch {
            dao.clearImportedHistory()
        }
    }

    // ----------------------------------------------------
    // Question Importing Execution (ZIP bundle creation)
    // ----------------------------------------------------

    fun executeImport() {
        val uriStr = settingsFlow.value?.zipFileUri ?: return
        val uri = Uri.parse(uriStr)

        val filesToExtract = _selectedFilePaths.value.toList()
        if (filesToExtract.isEmpty()) return

        _importProgress.value = 0.0f to "מכין קבצים לייבוא..."
        _exportedZipFile.value = null

        viewModelScope.launch {
            val resultFile = withContext(Dispatchers.IO) {
                val parsedQuestionsList = mutableListOf<Pair<String, List<Question>>>()
                val importedKeysSet = dao.getAllImportedKeys().toSet()

                for ((idx, filePath) in filesToExtract.withIndex()) {
                    _importProgress.value = (0.1f + (idx.toFloat() / filesToExtract.size) * 0.4f) to "קורא קובץ: ${filePath.substringAfterLast("/")}"
                    val qList = ZipHelper.readAndParseTxtFile(context, uri, filePath, importedKeysSet)
                    parsedQuestionsList.add(filePath to qList)
                }

                // Create the export ZIP containing anki_questions.txt and copy media items
                ZipHelper.createImportZip(context, uri, parsedQuestionsList) { fraction, desc ->
                    _importProgress.value = (0.5f + fraction * 0.5f) to desc
                }
            }

            if (resultFile != null) {
                // Success! Save imported question hashes to Room so they appear as already-imported
                val allImportedQuestions = _selectedFilePaths.value.flatMap { filePath ->
                    ZipHelper.readAndParseTxtFile(context, uri, filePath, emptySet())
                }.map { ImportedQuestion(it.key) }

                dao.markQuestionsImported(allImportedQuestions)
                _exportedZipFile.value = resultFile
            }
            _importProgress.value = null
        }
    }

    fun executePartialImport() {
        val uriStr = settingsFlow.value?.zipFileUri ?: return
        val uri = Uri.parse(uriStr)

        val filePath = _partialImportFile.value ?: return
        val selectedKeys = _selectedPartialKeys.value
        val questionsInFile = _partialQuestions.value

        val selectedQuestions = questionsInFile.filter { selectedKeys.contains(it.key) }
        if (selectedQuestions.isEmpty()) return

        _importProgress.value = 0.0f to "מייבא שאלות שנבחרו..."
        _exportedZipFile.value = null

        viewModelScope.launch {
            val resultFile = withContext(Dispatchers.IO) {
                val parsedQuestionsList = listOf(filePath to selectedQuestions)

                ZipHelper.createImportZip(context, uri, parsedQuestionsList) { fraction, desc ->
                    _importProgress.value = fraction to desc
                }
            }

            if (resultFile != null) {
                // Save imported hashes
                val importedObjects = selectedQuestions.map { ImportedQuestion(it.key) }
                dao.markQuestionsImported(importedObjects)
                _exportedZipFile.value = resultFile
                closePartialImport()
            }
            _importProgress.value = null
        }
    }

    fun clearExportedState() {
        _exportedZipFile.value = null
    }

    // ----------------------------------------------------
    // Direct AnkiDroid API Importing
    // ----------------------------------------------------

    private val _ankiImportResult = MutableStateFlow<String?>(null)
    val ankiImportResult: StateFlow<String?> = _ankiImportResult.asStateFlow()

    fun clearAnkiImportResult() {
        _ankiImportResult.value = null
    }

    fun isAnkiDroidAvailable(): Boolean {
        return AnkiDroidHelper.isApiAvailable(context)
    }

    fun executeAnkiDroidImport() {
        val uriStr = settingsFlow.value?.zipFileUri ?: return
        val uri = Uri.parse(uriStr)

        val filesToExtract = _selectedFilePaths.value.toList()
        if (filesToExtract.isEmpty()) return

        _importProgress.value = 0.0f to "מתחיל ייבוא ישיר לאנקידראויד..."
        _ankiImportResult.value = null

        viewModelScope.launch {
            try {
                val successCount = withContext(Dispatchers.IO) {
                    val importedKeysSet = dao.getAllImportedKeys().toSet()
                    var addedCount = 0
                    
                    val globalFallbackId = AnkiDroidHelper.getFirstModelOrFallback(context)
                    if (globalFallbackId == null) {
                        throw Exception("לא נמצאה תבנית כרטיסיות באנקידראויד. אנא ודא שהאפליקציה מותקנת ומוגדרת כראוי.")
                    }

                    val resolvedModelsCache = mutableMapOf<String, Long>()
                    fun getCachedModelId(modelName: String): Long? {
                        return resolvedModelsCache.getOrPut(modelName) {
                            AnkiDroidHelper.getOrInsertModel(context, modelName)
                                ?: AnkiDroidHelper.getFirstModelOrFallback(context)
                                ?: 0L
                        }.takeIf { it != 0L }
                    }

                    val allQuestionsToMark = mutableListOf<ImportedQuestion>()

                    for ((fileIdx, filePath) in filesToExtract.withIndex()) {
                        val percent = 0.1f + (fileIdx.toFloat() / filesToExtract.size) * 0.3f
                        val simpleName = filePath.substringAfterLast("/").substringBeforeLast(".")
                        _importProgress.value = percent to "קורא שאלות מתוך: $simpleName"
                        
                        val qList = ZipHelper.readAndParseTxtFile(context, uri, filePath, importedKeysSet)
                        if (qList.isEmpty()) continue

                        // Convert relative filePath to clean deck name
                        // e.g. "מאגר השאלות/רגיל/פנימית.txt" -> "מאגר השאלות::רגיל::פנימית"
                        val cleanPath = filePath.replace("\\", "/").removeSuffix(".txt")
                        val segments = cleanPath.split("/").filter { it.isNotBlank() }
                        val deckName = segments.joinToString("::")

                        _importProgress.value = percent to "יוצר/מוצא חפיסה באנקי: $deckName"
                        val deckId = AnkiDroidHelper.findOrCreateDeck(context, deckName)
                        if (deckId == null) {
                            throw Exception("שגיאה ביצירת החפיסה באנקידראויד: $deckName")
                        }

                        for ((cardIdx, q) in qList.withIndex()) {
                            val cardProgress = percent + ((cardIdx.toFloat() / qList.size) * (0.6f / filesToExtract.size))
                            _importProgress.value = cardProgress to "מייבא כרטיסייה: ${cardIdx + 1}/${qList.size} לחפיסה $simpleName"
                            
                            val modelName = AnkiDroidHelper.selectModelForFile(filePath, q.rawText)
                            val activeModelId = getCachedModelId(modelName)
                            if (activeModelId != null) {
                                val fieldsStr = AnkiDroidHelper.getFieldsForModel(modelName, q.rawText)
                                val insertedUri = AnkiDroidHelper.addNoteWithFields(context, deckId, activeModelId, fieldsStr, "מאגר_לאנקי")
                                if (insertedUri != null) {
                                    allQuestionsToMark.add(ImportedQuestion(q.key))
                                    addedCount++
                                }
                            }
                        }
                    }

                    if (allQuestionsToMark.isNotEmpty()) {
                        dao.markQuestionsImported(allQuestionsToMark)
                    }
                    addedCount
                }
                _ankiImportResult.value = "הייבוא הושלם בהצלחה! נוספו $successCount כרטיסיות ישירות לחשבון האנקידראויד שלך."
            } catch (e: Exception) {
                e.printStackTrace()
                _ankiImportResult.value = "הייבוא נכשל: ${e.message}"
            } finally {
                _importProgress.value = null
            }
        }
    }

    fun executeAnkiDroidPartialImport() {
        val uriStr = settingsFlow.value?.zipFileUri ?: return
        val uri = Uri.parse(uriStr)

        val filePath = _partialImportFile.value ?: return
        val selectedKeys = _selectedPartialKeys.value
        val questionsInFile = _partialQuestions.value

        val selectedQuestions = questionsInFile.filter { selectedKeys.contains(it.key) }
        if (selectedQuestions.isEmpty()) return

        _importProgress.value = 0.0f to "מייבא שאלות נבחרות לאנקידראויד..."
        _ankiImportResult.value = null

        viewModelScope.launch {
            try {
                val successCount = withContext(Dispatchers.IO) {
                    val globalFallbackId = AnkiDroidHelper.getFirstModelOrFallback(context)
                    if (globalFallbackId == null) {
                        throw Exception("לא נמצאה תבנית כרטיסיות באנקידראויד. אנא ודא שהאפליקציה מותקנת ומוגדרת כראוי.")
                    }

                    val resolvedModelsCache = mutableMapOf<String, Long>()
                    fun getCachedModelId(modelName: String): Long? {
                        return resolvedModelsCache.getOrPut(modelName) {
                            AnkiDroidHelper.getOrInsertModel(context, modelName)
                                ?: AnkiDroidHelper.getFirstModelOrFallback(context)
                                ?: 0L
                        }.takeIf { it != 0L }
                    }

                    // Deck name
                    val cleanPath = filePath.replace("\\", "/").removeSuffix(".txt")
                    val segments = cleanPath.split("/").filter { it.isNotBlank() }
                    val deckName = segments.joinToString("::")

                    val deckId = AnkiDroidHelper.findOrCreateDeck(context, deckName)
                    if (deckId == null) {
                        throw Exception("שגיאה ביצירת החפיסה באנקידראויד: $deckName")
                    }

                    var addedCount = 0
                    val allQuestionsToMark = mutableListOf<ImportedQuestion>()

                    for ((idx, q) in selectedQuestions.withIndex()) {
                        val fraction = idx.toFloat() / selectedQuestions.size
                        _importProgress.value = fraction to "מייבא כרטיסייה ${idx + 1}/${selectedQuestions.size}..."

                        val modelName = AnkiDroidHelper.selectModelForFile(filePath, q.rawText)
                        val activeModelId = getCachedModelId(modelName)
                        if (activeModelId != null) {
                            val fieldsStr = AnkiDroidHelper.getFieldsForModel(modelName, q.rawText)
                            val insertedUri = AnkiDroidHelper.addNoteWithFields(context, deckId, activeModelId, fieldsStr, "מאגר_לאנקי")
                            if (insertedUri != null) {
                                allQuestionsToMark.add(ImportedQuestion(q.key))
                                addedCount++
                            }
                        }
                    }

                    if (allQuestionsToMark.isNotEmpty()) {
                        dao.markQuestionsImported(allQuestionsToMark)
                    }
                    addedCount
                }
                _ankiImportResult.value = "ייבוא חלקי הושלם בהצלחה! נוספו $successCount כרטיסיות ישירות לאנקידראויד."
                closePartialImport()
            } catch (e: Exception) {
                e.printStackTrace()
                _ankiImportResult.value = "הייבוא נכשל: ${e.message}"
            } finally {
                _importProgress.value = null
            }
        }
    }
}
