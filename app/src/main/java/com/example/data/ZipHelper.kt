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

    private fun safeGetCharset(name: String): java.nio.charset.Charset? {
        return try {
            java.nio.charset.Charset.forName(name)
        } catch (e: Exception) {
            null
        }
    }

    fun decodeWindows1255(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder(bytes.size)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            when (i) {
                in 0x00..0x7F -> sb.append(i.toChar())
                in 0xE0..0xFA -> sb.append((i - 0xE0 + 0x05D0).toChar())
                in 0xC0..0xDF -> sb.append((i - 0xC0 + 0x05B0).toChar())
                0x91 -> sb.append('‘')
                0x92 -> sb.append('’')
                0x93 -> sb.append('“')
                0x94 -> sb.append('”')
                else -> sb.append(i.toChar())
            }
        }
        return sb.toString()
    }

    fun decodeIBM862(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder(bytes.size)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            when (i) {
                in 0x00..0x7F -> sb.append(i.toChar())
                in 0x80..0x9A -> sb.append((i - 0x80 + 0x05D0).toChar())
                else -> sb.append(i.toChar())
            }
        }
        return sb.toString()
    }

    fun decodeBytesToHebrewString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. Check for UTF-16 BE / LE BOM
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
        }
        
        // 2. Check for UTF-8 BOM
        if (bytes.size >= 3) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
        }

        // 3. Try decoding as UTF-8
        val utf8String = String(bytes, Charsets.UTF_8)
        // If it doesn't contain the replacement character '\uFFFD', it's valid UTF-8!
        if (!utf8String.contains('\uFFFD')) {
            return utf8String
        }

        // If it isn't valid UTF-8, detect between legacy Windows-1255/ISO-8859-8 and IBM862 using character counts
        var score1255 = 0
        var score862 = 0
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            if (i in 0xE0..0xFA) {
                score1255++
            }
            if (i in 0x80..0x9A) {
                score862++
            }
        }

        return if (score862 > score1255) {
            decodeIBM862(bytes)
        } else {
            decodeWindows1255(bytes)
        }
    }

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
            if (isSevenZip(context, zipUri)) {
                tempFile = copyToTempFile(context, zipUri)
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
                            return decodeBytesToHebrewString(bytes)
                        }
                        entry = sevenZ.nextEntry
                    }
                }
            } else {
                val contentResolver = context.contentResolver
                // Try finding repo_struct.json with different ZIP header encoding fallback
                val charsetsToTry = listOfNotNull(
                    Charsets.UTF_8,
                    safeGetCharset("windows-1255"),
                    safeGetCharset("ISO-8859-8"),
                    safeGetCharset("IBM862")
                )
                for (charset in charsetsToTry) {
                    try {
                        contentResolver.openInputStream(zipUri)?.use { inputStream ->
                            ZipInputStream(inputStream, charset).use { zipInput ->
                                var entry: ZipEntry? = zipInput.nextEntry
                                while (entry != null) {
                                    val name = entry.name
                                    if (name.endsWith("repo_struct.json", ignoreCase = true)) {
                                        val bytes = zipInput.readBytes()
                                        return decodeBytesToHebrewString(bytes)
                                    }
                                    zipInput.closeEntry()
                                    entry = zipInput.nextEntry
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
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
            if (isSevenZip(context, zipUri)) {
                tempFile = copyToTempFile(context, zipUri)
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
                                val text = decodeBytesToHebrewString(bytes)
                                parseTxtContent(text, filePath, importedKeys, questions)
                                break
                            }
                        }
                        entry = sevenZ.nextEntry
                    }
                }
            } else {
                val entryName = filePath.replace("\\", "/")
                var found = false
                // Try opening zip using different entry name charsets
                val charsetsToTry = listOfNotNull(
                    Charsets.UTF_8,
                    safeGetCharset("windows-1255"),
                    safeGetCharset("ISO-8859-8"),
                    safeGetCharset("IBM862")
                )
                for (charset in charsetsToTry) {
                    if (found) break
                    try {
                        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                            ZipInputStream(inputStream, charset).use { zipInput ->
                                var entry: ZipEntry? = zipInput.nextEntry
                                while (entry != null) {
                                    val name = entry.name.replace("\\", "/")
                                    if (name.equals(entryName, ignoreCase = true)) {
                                        val bytes = zipInput.readBytes()
                                        val text = decodeBytesToHebrewString(bytes)
                                        parseTxtContent(text, filePath, importedKeys, questions)
                                        found = true
                                        break
                                    }
                                    zipInput.closeEntry()
                                    entry = zipInput.nextEntry
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
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

    private fun parseTxtContent(text: String, filePath: String, importedKeys: Set<String>, questions: MutableList<Question>) {
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parseQ = parseAnkiLine(trimmed, filePath, importedKeys)
                if (parseQ != null) {
                    questions.add(parseQ)
                }
            }
        }
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
                        tempSourceFile = copyToTempFile(context, zipUri)
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
                        // ZIP - Streaming copy in a single pass, absolutely NO copy to temp file!
                        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                            ZipInputStream(inputStream).use { zipInput ->
                                var entry = zipInput.nextEntry
                                var copiedCount = 0
                                val targetMediaLower = mediaNames.map { it.lowercase() }.toSet()
                                val targetToRealName = mediaNames.associateBy { it.lowercase() }
                                
                                val buffer = ByteArray(4096)
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val path = entry.name.replace("\\", "/")
                                        if (path.contains("/media/", ignoreCase = true) || path.startsWith("media/", ignoreCase = true)) {
                                            val filename = path.substringAfterLast("/").lowercase()
                                            if (targetMediaLower.contains(filename)) {
                                                val realName = targetToRealName[filename] ?: filename
                                                onProgress(
                                                    0.2f + (copiedCount.toFloat() / totalMedia) * 0.75f,
                                                    "מעתיק קבצי מדיה: $realName ($copiedCount/$totalMedia)"
                                                )
                                                zos.putNextEntry(ZipEntry(realName))
                                                var bytesRead = zipInput.read(buffer)
                                                while (bytesRead != -1) {
                                                    zos.write(buffer, 0, bytesRead)
                                                    bytesRead = zipInput.read(buffer)
                                                }
                                                zos.closeEntry()
                                                copiedCount++
                                            }
                                        }
                                    }
                                    zipInput.closeEntry()
                                    entry = zipInput.nextEntry
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
