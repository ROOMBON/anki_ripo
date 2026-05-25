package com.example.data

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Question(
    val key: String, // SHA-1 of the raw line to uniquely identify this question
    val front: String,
    val back: String,
    val rawText: String,
    val sourceFile: String,
    val isImported: Boolean = false
)

object ZipHelper {

    fun isSevenZip(context: Context, uri: Uri): Boolean {
        val fileName = getFileName(context, uri)
        if (fileName.endsWith(".7z", ignoreCase = true)) return true
        
        val path = uri.path
        if (path != null && path.endsWith(".7z", ignoreCase = true)) return true
        
        return false
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        result = cursor.getString(idx)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: ""
    }

    /**
     * Scans the ZIP/7Z file for a 'repo_struct.json' at the root level or anywhere.
     * Extracts its contents and returns it as a String.
     */
    fun findAndExtractRepoStruct(context: Context, zipUri: Uri): String? {
        var tempFile: File? = null
        try {
            tempFile = copyToTempFile(context, zipUri)
            if (isSevenZip(context, zipUri)) {
                SevenZFile(tempFile).use { sevenZ ->
                    var entry = sevenZ.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith("repo_struct.json", ignoreCase = true)) {
                            val size = entry.size.toInt()
                            val bytes = ByteArray(size)
                            var offset = 0
                            while (offset < size) {
                                val read = sevenZ.read(bytes, offset, size - offset)
                                if (read == -1) break
                                offset += read
                            }
                            return String(bytes, Charsets.UTF_8)
                        }
                        entry = sevenZ.nextEntry
                    }
                }
            } else {
                val contentResolver = context.contentResolver
                contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipInput ->
                        var entry: ZipEntry? = zipInput.getNextEntry()
                        while (entry != null) {
                            val name = entry.name
                            if (name.endsWith("repo_struct.json", ignoreCase = true)) {
                                val reader = BufferedReader(InputStreamReader(zipInput, "UTF-8"))
                                val stringBuilder = StringBuilder()
                                var line: String? = reader.readLine()
                                while (line != null) {
                                    stringBuilder.append(line).append("\n")
                                    line = reader.readLine()
                                }
                                return stringBuilder.toString()
                            }
                            zipInput.closeEntry()
                            entry = zipInput.getNextEntry()
                        }
                    }
                }
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            tempFile?.delete()
        }
    }

    /**
     * Reads a specific TXT file inside the ZIP/7Z and parses its lines into Question objects.
     */
    fun readAndParseTxtFile(context: Context, zipUri: Uri, filePath: String, importedKeys: Set<String>): List<Question> {
        val questions = mutableListOf<Question>()
        var tempFile: File? = null
        try {
            tempFile = copyToTempFile(context, zipUri)
            if (isSevenZip(context, zipUri)) {
                SevenZFile(tempFile).use { sevenZ ->
                    val entryName = filePath.replace("\\", "/")
                    var entry = sevenZ.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.replace("\\", "/")
                            if (name.equals(entryName, ignoreCase = true)) {
                                val size = entry.size.toInt()
                                val bytes = ByteArray(size)
                                var offset = 0
                                while (offset < size) {
                                    val read = sevenZ.read(bytes, offset, size - offset)
                                    if (read == -1) break
                                    offset += read
                                }
                                val text = String(bytes, Charsets.UTF_8)
                                text.lineSequence().forEach { line ->
                                    val trimmed = line.trim()
                                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                        val parseQ = parseAnkiLine(trimmed, filePath, importedKeys)
                                        if (parseQ != null) {
                                            questions.add(parseQ)
                                        }
                                    }
                                }
                                break
                            }
                        }
                        entry = sevenZ.nextEntry
                    }
                }
            } else {
                ZipFile(tempFile).use { zipFile ->
                    val entryName = filePath.replace("\\", "/")
                    var entry = zipFile.getEntry(entryName)
                    if (entry == null) {
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val next = entries.nextElement()
                            if (next.name.replace("\\", "/").equals(entryName, ignoreCase = true)) {
                                entry = next
                                break
                            }
                        }
                    }

                    if (entry != null) {
                        zipFile.getInputStream(entry).use { stream ->
                            val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
                            var line = reader.readLine()
                            while (line != null) {
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                    val parseQ = parseAnkiLine(trimmed, filePath, importedKeys)
                                    if (parseQ != null) {
                                        questions.add(parseQ)
                                    }
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempFile?.delete()
        }
        return questions
    }

    private fun parseAnkiLine(line: String, sourceFile: String, importedKeys: Set<String>): Question? {
        val fields = line.split("\t")
        if (fields.isEmpty()) return null
        val front = fields.getOrNull(0)?.cleanHtml() ?: ""
        val back = fields.getOrNull(1)?.cleanHtml() ?: (fields.drop(1).joinToString(" | ").cleanHtml())
        if (front.isBlank()) return null
        val key = hashString(line)
        return Question(
            key = key,
            front = front,
            back = back,
            rawText = line,
            sourceFile = sourceFile,
            isImported = importedKeys.contains(key)
        )
    }

    private fun String.cleanHtml(): String {
        return this.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Packages selected questions and their media files into a ZIP output file.
     * This file is saved in cache and can be shared.
     */
    fun createImportZip(
        context: Context,
        zipUri: Uri,
        questions: List<Pair<String, List<Question>>>, // File path to matching questions list
        onProgress: (Float, String) -> Unit
    ): File? {
        var tempSourceFile: File? = null
        try {
            onProgress(0.05f, "מתחיל הכנת קובץ...")
            tempSourceFile = copyToTempFile(context, zipUri)
            val outputZipFile = File(context.cacheDir, "anki_import_${System.currentTimeMillis()}.zip")
            
            val is7z = isSevenZip(context, zipUri)
            
            ZipOutputStream(FileOutputStream(outputZipFile)).use { zos ->
                // 1. Write the main TXT file
                zos.putNextEntry(ZipEntry("anki_questions.txt"))
                val txtBuilder = StringBuilder()
                txtBuilder.append("#separator:tab\n")
                txtBuilder.append("#html:true\n")
                txtBuilder.append("#tags:מאגר_לאנקי\n")
                
                val allQuestions = questions.flatMap { it.second }
                for (q in allQuestions) {
                    txtBuilder.append(q.rawText).append("\n")
                }
                
                zos.write(txtBuilder.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 2. Extract referenced media files
                val mediaNames = findMediaReferences(allQuestions)
                val totalMedia = mediaNames.size
                
                if (totalMedia > 0) {
                    if (is7z) {
                        SevenZFile(tempSourceFile).use { sevenZ ->
                            var entry = sevenZ.nextEntry
                            var extractedCount = 0
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val path = entry.name.replace("\\", "/")
                                    if (path.contains("/media/", ignoreCase = true) || path.startsWith("media/", ignoreCase = true)) {
                                        val filename = path.substringAfterLast("/").lowercase()
                                        val matchingMediaName = mediaNames.find { it.lowercase() == filename }
                                        if (matchingMediaName != null) {
                                            onProgress(
                                                0.2f + (extractedCount.toFloat() / totalMedia) * 0.75f,
                                                "מעתיק קבצי מדיה: $matchingMediaName ($extractedCount/$totalMedia)"
                                            )
                                            zos.putNextEntry(ZipEntry(matchingMediaName))
                                            val buffer = ByteArray(4096)
                                            var bytesRead = sevenZ.read(buffer)
                                            while (bytesRead != -1) {
                                                zos.write(buffer, 0, bytesRead)
                                                bytesRead = sevenZ.read(buffer)
                                            }
                                            zos.closeEntry()
                                            extractedCount++
                                        }
                                    }
                                }
                                entry = sevenZ.nextEntry
                            }
                        }
                    } else {
                        ZipFile(tempSourceFile).use { sourceZip ->
                            val mediaEntriesMap = scanMediaInZip(sourceZip)
                            for ((idx, mediaName) in mediaNames.withIndex()) {
                                onProgress(
                                    0.2f + (idx.toFloat() / totalMedia) * 0.75f,
                                    "מעתיק קבצי מדיה: $mediaName ($idx/$totalMedia)"
                                )

                                val sourceEntry = mediaEntriesMap[mediaName.lowercase()]
                                if (sourceEntry != null) {
                                    zos.putNextEntry(ZipEntry(mediaName))
                                    sourceZip.getInputStream(sourceEntry).use { input ->
                                        input.copyTo(zos)
                                    }
                                    zos.closeEntry()
                                }
                            }
                        }
                    }
                }
            }
            onProgress(1.0f, "הושלם!")
            return outputZipFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            tempSourceFile?.delete()
        }
    }

    private fun findMediaReferences(questions: List<Question>): Set<String> {
        val filenames = mutableSetOf<String>()
        val soundRegex = Regex("\\[sound:([^\\]]+)\\]")
        val imgRegex = Regex("src=\"([^\"]+)\"")
        for (q in questions) {
            soundRegex.findAll(q.rawText).forEach { match ->
                filenames.add(match.groupValues[1])
            }
            imgRegex.findAll(q.rawText).forEach { match ->
                filenames.add(match.groupValues[1])
            }
        }
        return filenames
    }

    /**
     * Map all file names in ZIP to their ZipEntries under any 'media' directories
     */
    private fun scanMediaInZip(zip: ZipFile): Map<String, ZipEntry> {
        val entriesMap = mutableMapOf<String, ZipEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory) {
                val path = entry.name.replace("\\", "/")
                if (path.contains("/media/", ignoreCase = true) || path.startsWith("media/", ignoreCase = true)) {
                    val filename = path.substringAfterLast("/").lowercase()
                    entriesMap[filename] = entry
                }
            }
        }
        return entriesMap
    }

    private fun copyToTempFile(context: Context, uri: Uri): File {
        val is7z = uri.path?.endsWith(".7z", ignoreCase = true) == true
        val tempFile = File.createTempFile("anki_repo_temp", if (is7z) ".7z" else ".zip", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Failed to open Uri input stream")
        return tempFile
    }
}
