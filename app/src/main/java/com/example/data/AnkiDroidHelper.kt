package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri

object AnkiDroidHelper {
    private val KNOWN_AUTHORITIES = listOf(
        "com.ichi2.anki.providers.CardContentProvider",
        "com.ichi2.anki.parallel_A.providers.CardContentProvider",
        "com.ichi2.anki.parallel_B.providers.CardContentProvider",
        "com.ichi2.anki.parallel_C.providers.CardContentProvider",
        "com.ichi2.anki.debug.providers.CardContentProvider",
        "com.ichi2.anki.alpha.providers.CardContentProvider",
        "com.ichi2.anki.A.providers.CardContentProvider",
        "com.ichi2.anki.B.providers.CardContentProvider",
        "com.ichi2.anki.C.providers.CardContentProvider",
        "com.ichi2.anki.parallel.providers.CardContentProvider"
    )

    enum class ConnectionStatus {
        OK,
        API_DISABLED,
        NOT_INSTALLED
    }

    @Volatile
    private var cachedActiveAuthority: String? = null

    fun getActiveAuthority(context: Context): String {
        cachedActiveAuthority?.let { return it }

        val pm = context.packageManager
        // 1. First try checking if the packages are installed via getPackageInfo
        // Highly reliable on Android 11+ because of `<package>` tags under `<queries>` in AndroidManifest.xml
        for (auth in KNOWN_AUTHORITIES) {
            val packageName = auth.removeSuffix(".providers.CardContentProvider")
            try {
                pm.getPackageInfo(packageName, 0)
                cachedActiveAuthority = auth
                return auth
            } catch (e: Exception) {
                // Ignore and proceed
            }
        }

        // 2. Try the PackageManager resolveContentProvider check
        for (auth in KNOWN_AUTHORITIES) {
            try {
                val info = pm.resolveContentProvider(auth, 0)
                if (info != null) {
                    cachedActiveAuthority = auth
                    return auth
                }
            } catch (e: Exception) {
                // Ignore PackageManager quirks
            }
        }

        // 3. As a robust fallback, verify resolving via ContentResolver query
        for (auth in KNOWN_AUTHORITIES) {
            val uri = Uri.parse("content://$auth/decks")
            try {
                val cursor = context.contentResolver.query(uri, arrayOf("id"), null, null, null)
                if (cursor != null) {
                    cursor.close()
                    cachedActiveAuthority = auth
                    return auth
                }
            } catch (e: SecurityException) {
                // SecurityException means the provider is DEFINITELY installed
                cachedActiveAuthority = auth
                return auth
            } catch (e: Exception) {
                // Other exceptions typical of non-existent providers
            }
        }
        
        return "com.ichi2.anki.providers.CardContentProvider" // Fallback
    }

    fun getContentUri(context: Context): Uri {
        return Uri.parse("content://${getActiveAuthority(context)}")
    }

    fun getPermissionName(context: Context): String {
        val auth = getActiveAuthority(context)
        val packageName = auth.removeSuffix(".providers.CardContentProvider")
        return "$packageName.permission.READ_WRITE_DATABASE"
    }

    fun getConnectionStatus(context: Context): ConnectionStatus {
        val auth = getActiveAuthority(context)
        
        // 1. Check if the corresponding package is installed first
        val packageName = auth.removeSuffix(".providers.CardContentProvider")
        val pm = context.packageManager
        val isInstalled = try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            try {
                pm.resolveContentProvider(auth, 0) != null
            } catch (ex: Exception) {
                false
            }
        }

        if (!isInstalled) {
            return ConnectionStatus.NOT_INSTALLED
        }

        // 2. Query to see if the API is enabled or disabled (handles SecurityExceptions)
        val uri = Uri.parse("content://$auth/decks")
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf("id"), null, null, null)
            if (cursor != null) {
                cursor.close()
            }
            ConnectionStatus.OK
        } catch (e: SecurityException) {
            ConnectionStatus.API_DISABLED
        } catch (e: Exception) {
            // General query failures after confirming package is installed means either API is disabled or pending approval
            ConnectionStatus.API_DISABLED
        }
    }

    const val AUTHORITY = "com.ichi2.anki.providers.CardContentProvider"
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
    
    // Permission required by AnkiDroid ContentProvider
    const val PERMISSION_READ_WRITE = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    
    /**
     * Checks if AnkiDroid is installed and its ContentProvider API is available.
     */
    fun isApiAvailable(context: Context): Boolean {
        return getConnectionStatus(context) != ConnectionStatus.NOT_INSTALLED
    }
    
    /**
     * Fetches all decks from AnkiDroid.
     */
    fun getDeckList(context: Context): Map<Long, String> {
        val decks = mutableMapOf<Long, String>()
        val uri = Uri.withAppendedPath(getContentUri(context), "decks")
        try {
            val cursor = context.contentResolver.query(uri, arrayOf("id", "name"), null, null, null)
            cursor?.use {
                val idCol = it.getColumnIndex("id")
                val nameCol = it.getColumnIndex("name")
                if (idCol >= 0 && nameCol >= 0) {
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol)
                        decks[id] = name
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return decks
    }
    
    /**
     * Fetches all note models/templates from AnkiDroid.
     */
    fun getModelList(context: Context): Map<Long, String> {
        val models = mutableMapOf<Long, String>()
        val uri = Uri.withAppendedPath(getContentUri(context), "models")
        try {
            val cursor = context.contentResolver.query(uri, arrayOf("id", "name"), null, null, null)
            cursor?.use {
                val idCol = it.getColumnIndex("id")
                val nameCol = it.getColumnIndex("name")
                if (idCol >= 0 && nameCol >= 0) {
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol)
                        models[id] = name
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return models
    }
    
    /**
     * Resolves a deck by name. If it doesn't exist, requests AnkiDroid to create it.
     */
    fun findOrCreateDeck(context: Context, name: String): Long? {
        val decks = getDeckList(context)
        for ((id, dname) in decks) {
            if (dname.equals(name, ignoreCase = true)) {
                return id
            }
        }
        
        val uri = Uri.withAppendedPath(getContentUri(context), "decks")
        val values = ContentValues().apply {
            put("name", name)
        }
        return try {
            val resultUri = context.contentResolver.insert(uri, values)
            resultUri?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Tries to find his "Basic" note model, falling back to any available model.
     */
    fun getFirstModelOrFallback(context: Context): Long? {
        val models = getModelList(context)
        if (models.isEmpty()) return null
        
        for ((id, name) in models) {
            if (name.contains("Basic", ignoreCase = true)) {
                return id
            }
        }
        return models.keys.firstOrNull()
    }
    
    /**
     * Retrieves the custom AnkiQaBank model Id if registered.
     */
    fun getAnkiQaBankModelId(context: Context): Long? {
        val models = getModelList(context)
        for ((id, name) in models) {
            if (name.equals("AnkiQaBank", ignoreCase = true)) {
                return id
            }
        }
        return null
    }

    /**
     * Creates a new note model/template in AnkiDroid.
     */
    fun addNewModel(
        context: Context,
        name: String,
        fields: Array<String>,
        cards: Array<String>,
        qfmt: Array<String>,
        afmt: Array<String>,
        css: String = "",
        type: Int = 0
    ): Long? {
        val uri = Uri.withAppendedPath(getContentUri(context), "models")
        val values = ContentValues().apply {
            put("name", name)
            put("flds", fields.joinToString("\u251f"))
            put("cards", cards.joinToString("\u251f"))
            put("qfmt", qfmt.joinToString("\u251f"))
            put("afmt", afmt.joinToString("\u251f"))
            put("css", css)
            put("type", type)
        }
        return try {
            val resultUri = context.contentResolver.insert(uri, values)
            resultUri?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get or insert one of the 5 requested study note models, or fell back to standard.
     */
    fun getOrInsertModel(context: Context, modelName: String): Long? {
        val models = getModelList(context)
        for ((id, name) in models) {
            if (name.equals(modelName, ignoreCase = true)) {
                return id
            }
        }

        return when (modelName) {
            "AnkiDaf" -> {
                val fields = arrayOf("Front", "Back", "מקור", "hint", "Sponsorship")
                val cards = arrayOf("Card 1")
                val qfmt = arrayOf("""
{{Front}}
<br>
{{hint:hint}}
                """.trimIndent())
                val afmt = arrayOf("""
{{Front}}

<hr id=answer>

{{Back}}

<div style='font-family: Arial; font-size: 14px;'>{{מקור}}</div>
<div style='font-family: Arial; font-size: 6px;'><br></div>
<div style='font-family: Arial; font-size: 10px;'>©<br> <a href="http://www.ankidaf.com">www.ankidaf.com</a>
<br><a href="http://www.masterdafaweek.com">www.masterdafaweek.com</a>
<div style='font-family: Arial; font-size: 10px;'>{{Sponsorship}}</div>
                """.trimIndent())
                val css = """
.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}
                """.trimIndent()
                addNewModel(context, modelName, fields, cards, qfmt, afmt, css)
            }
            "Anki-Daf-cloze" -> {
                val fields = arrayOf("Text", "מקור", "Sponsorship")
                val cards = arrayOf("Card 1")
                val qfmt = arrayOf("""
{{cloze:Text}}
                """.trimIndent())
                val afmt = arrayOf("""
{{cloze:Text}}<br>
<div style='font-family: Arial; font-size: 14px;'>{{מקור}}</div>
<div style='font-family: Arial; font-size: 6px;'><br></div>
<div style='font-family: Arial; font-size: 10px;'>©<br> <a href="http://www.ankitorah.com/anki-daf">www.ankitorah.com/anki-daf</a>
<br><a href="http://www.masterdafaweek.com">www.masterdafaweek.com</a>
<div style='font-family: Arial; font-size: 10px;'>{{Sponsorship}}</div>
                """.trimIndent())
                val css = """
.card {
 font-family: arial;
 font-size: 20px;
 text-align: center;
 color: black;
 background-color: white;
}

.cloze {
 font-weight: bold;
 color: blue;
}
                """.trimIndent()
                addNewModel(context, modelName, fields, cards, qfmt, afmt, css, type = 0)
            }
            "תת_לשימוש_המאגר_המוכן_חפץ_חיים" -> {
                val fields = arrayOf("שאלה", "תשובה", "מקור", "רמז")
                val cards = arrayOf("חפץ_חיים")
                val qfmt = arrayOf("""
<div style='font-family: livorna; font-size: 20px; font-weight: bold;text-align:center  '>
שאלה
</div>
<table style= " width: 100%; " border="0"
 cellpadding="10" cellspacing="10">
  <tbody >
    <tr>
      <td class = "colors" style="; border-radius: 5px; ">
      <span style=" font-weight: bold;  ">{{edit:מקור}}</span>
{{#מקור}}

 {{/מקור}}
{{edit:שאלה}}
<div style='font-family: livorna ; font-size: 20px; color: blue;'>{{edit:hint:רמז}}</div>

</td>

    </tr>
  </tbody>
</table>

<div id="zl1" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">לעילוי נשמת ר' יעקב בן מרדכי וזוהרה ז"ל</span></strong></span></div>
<div id="zl2" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">ואולגה בת חיים ואסתר ע"ה.</span></strong></span></div>
<div id="ts" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">בכל עניין שנוגע לשאלות אלו ניתן לפנות למערכת הפצת אנקי- <a href="mailto:anki4tora@gmail.com">anki4tora@gmail.com</a></span></strong></span></div>
                """.trimIndent())
                val afmt = arrayOf("""
<div style='font-family: livorna; font-size: 20px; font-weight: bold;text-align:center  '>
שאלה
</div>
<table style= " width: 100%; " border="0"
 cellpadding="10" cellspacing="10">
  <tbody >
    <tr>
      <td class = "colors" style="; border-radius: 5px; ">
      <span style=" font-weight: bold;  ">{{edit:מקור}}</span>
{{#מקור}}

 {{/מקור}}
{{edit:שאלה}}
<div style='font-family: livorna ; font-size: 20px; color: blue;'>{{edit:hint:רמז}}</div>

</td>

    </tr>
  </tbody>
</table>

<hr id=answer>
<div style='font-family: livorna ;  font-family: livorna; font-size: 20px; font-weight: bold;text-align:center '>
תשובה
</div>
  </tbody>
<table style=" width: 100%;" border="0"
 cellpadding="10" cellspacing="10">
  <tbody>
    <tr>
     <td class = "colors" style="; border-radius: 5px; ">{{edit:תשובה}}</td>
    </tr>
  </tbody>
</table>

<div id="zl1" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">לעילוי נשמת ר' יעקב בן מרדכי וזוהרה ז"ל</span></strong></span></div>
<div id="zl2" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">ואולגה בת חיים ואסתר ע"ה.</span></strong></span></div>
<div id="ts" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">בכל עניין שנוגע לשאלות אלו ניתן לפנות למערכת הפצת אנקי- <a href="mailto:anki4tora@gmail.com">anki4tora@gmail.com</a></span></strong></span></div>
                """.trimIndent())
                val css = """
.card {
 font-family: livorna;
 font-size: 24px;
 text-align: center;
  background-color:lightgray;
text-align: justify;
direction: rtl;
unicode-bidi: embed;
}
.colors{
 color: black;
 background-color:lightyellow;
}
.nightMode .colors{
 color: white;
 background-color:gray;
}
                """.trimIndent()
                addNewModel(context, modelName, fields, cards, qfmt, afmt, css)
            }
            "תת_לשימוש_המאגר_המוכן_משנב" -> {
                val fields = arrayOf("שאלה", "תשובה", "מיקום (חלק סימן וסעיף)", "רמז")
                val cards = arrayOf("משנ\"ב")
                val qfmt = arrayOf("""
<div style='font-family: livorna; font-size: 20px; font-weight: bold;text-align:center  '>
שאלה
</div>
<table style= " width: 100%; " border="0"
 cellpadding="10" cellspacing="10">
  <tbody >
    <tr>
      <td class = "colors" style="; border-radius: 5px; ">
      <span style=" font-weight: bold;  ">{{edit:מיקום (חלק סימן וסעיף)}}</span>
{{#מיקום (חלק סימן וסעיף)}}

 {{/מיקום (חלק סימן וסעיף)}}
{{edit:שאלה}}
<div style='font-family: livorna ; font-size: 20px; color: blue;'>{{edit:hint:רמז}}</div>

</td>

    </tr>
  </tbody>
</table>

<div id="zl1" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">לעילוי נשמת ר' יעקב בן מרדכי וזוהרה ז"ל</span></strong></span></div>
<div id="zl2" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">ואולגה בת חיים ואסתר ע"ה.</span></strong></span></div>
<div id="ts" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">בכל עניין שנוגע לשאלות אלו ניתן לפנות למערכת הפצת אנקי- <a href="mailto:anki4tora@gmail.com">anki4tora@gmail.com</a></span></strong></span></div>
                """.trimIndent())
                val afmt = arrayOf("""
<div style='font-family: livorna; font-size: 20px; font-weight: bold;text-align:center  '>
שאלה
</div>
<table style= " width: 100%; " border="0"
 cellpadding="10" cellspacing="10">
  <tbody >
    <tr>
      <td class = "colors" style="; border-radius: 5px; ">
      <span style=" font-weight: bold;  ">{{edit:מיקום (חלק סימן וסעיף)}}</span>
{{#מיקום (חלק סימן וסעיף)}}

 {{/מיקום (חלק סימן וסעיף)}}
{{edit:שאלה}}
<div style='font-family: livorna ; font-size: 20px; color: blue;'>{{edit:hint:רמז}}</div>

</td>

    </tr>
  </tbody>
</table>

<hr id=answer>
<div style='font-family: livorna ;  font-family: livorna; font-size: 20px; font-weight: bold;text-align:center '>
תשובה
</div>
  </tbody>
<table style=" width: 100%;" border="0"
 cellpadding="10" cellspacing="10">
  <tbody>
    <tr>
     <td class = "colors" style="; border-radius: 5px; ">{{edit:תשובה}}</td>
    </tr>
  </tbody>
</table>

<div id="zl1" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">לעילוי נשמת ר' יעקב בן מרדכי וזוהרה ז"ל</span></strong></span></div>
<div id="zl2" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">ואולגה בת חיים ואסתר ע"ה.</span></strong></span></div>
<div id="ts" style="text-align: center;"><span style="font-size: 10pt;"><strong><span style="font-family: 'arial black', sans-serif;">בכל עניין שנוגע לשאלות אלו ניתן לפנות למערכת הפצת אנקי- <a href="mailto:anki4tora@gmail.com">anki4tora@gmail.com</a></span></strong></span></div>
                """.trimIndent())
                val css = """
.card {
 font-family: livorna;
 font-size: 24px;
 text-align: center;
  background-color:lightgray;
text-align: justify;
direction: rtl;
unicode-bidi: embed;
}
.colors{
 color: black;
 background-color:lightyellow;
}
.nightMode .colors{
 color: white;
 background-color:gray;
}
                """.trimIndent()
                addNewModel(context, modelName, fields, cards, qfmt, afmt, css)
            }
            "תת - שאלה רגילה - (לשימוש המאגר)" -> {
                val fields = arrayOf("שאלה", "תשובה", "מקור", "רמז")
                val cards = arrayOf("שאלה רגילה")
                val qfmt = arrayOf("""
<div style='font-family: livorna; font-size: 20px; font-weight: bold;text-align:center  '>
שאלה
</div>
<table style= " width: 100%; " border="0"
 cellpadding="10" cellspacing="10">
  <tbody >
    <tr>
      <td class = "colors" style="; border-radius: 5px; "><span style=" font-weight: bold;  ">{{edit:מקור}}</span>
{{edit:שאלה}}
<div style='font-family: livorna ; font-size: 20px; color: blue;'>{{edit:hint:רמז}}</div>

</td>

         </tr>
       </tbody>
     </table>
                """.trimIndent())
                val afmt = arrayOf("""
{{FrontSide}}
<hr id=answer>
<div style='font-family: livorna ;  font-family: livorna; font-size: 20px; font-weight: bold;text-align:center '>
תשובה
</div>
  </tbody>
<table style=" width: 100%;" border="0"
 cellpadding="10" cellspacing="10">
  <tbody>
    <tr>
     <td class = "colors" style="; border-radius: 5px; ">{{edit:תשובה}}</td>
    </tr>
  </tbody>
</table>
                """.trimIndent())
                val css = """
.card {
 font-family: livorna;
 font-size: 18pt;
 text-align: center;
  background-color:lightgray;
text-align: justify;
direction: rtl;
unicode-bidi: embed;
}
.colors{
 color: black;
 background-color:lightyellow;
}
.nightMode .colors{
 color: white;
 background-color:gray;
}
                """.trimIndent()
                addNewModel(context, modelName, fields, cards, qfmt, afmt, css)
            }
            else -> null
        }
    }

    /**
     * Finds the custom "AnkiQaBank" model or inserts it, creating an optimized, beautiful layout for Hebrew Q&A.
     */
    fun getOrInsertAnkiQaBankModel(context: Context): Long? {
        val existingId = getAnkiQaBankModelId(context)
        if (existingId != null) return existingId

        val modelName = "AnkiQaBank"
        val fields = arrayOf("Front", "Back")
        val cards = arrayOf("Card 1")
        val qfmt = arrayOf("<div class=\"card\">{{Front}}</div>")
        val afmt = arrayOf("<div class=\"card\">{{Front}}<hr id=answer>{{Back}}</div>")
        val css = """
            .card {
                font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
                font-size: 22px;
                text-align: center;
                color: #1A1A1A;
                background-color: #FFFFFF;
                padding: 24px;
                line-height: 1.6;
                direction: rtl;
                word-wrap: break-word;
            }
            .card.dark {
                color: #E0E0E0;
                background-color: #121212;
            }
            #answer {
                border: 0;
                height: 1px;
                background-image: linear-gradient(to right, rgba(0,0,0,0), rgba(0,0,0,0.35), rgba(0,0,0,0));
                margin: 24px 0;
            }
            img {
                max-width: 100%;
                height: auto;
                margin-top: 10px;
                border-radius: 8px;
            }
        """.trimIndent()

        return addNewModel(context, modelName, fields, cards, qfmt, afmt, css)
    }

    /**
     * Selects appropriate custom note model name based on file path and line content.
     */
    fun selectModelForFile(filePath: String, rawText: String): String {
        val cleanPath = filePath.replace("\\", "/").lowercase()
        val cleanLine = rawText.lowercase()
        
        if (cleanPath.contains("cloze") || cleanPath.contains("קלוז") || cleanLine.contains("{{cloze:") || cleanLine.contains("{{c1::")) {
            return "Anki-Daf-cloze"
        }
        if (cleanPath.contains("משנב") || cleanPath.contains("משנ\"ב") || cleanPath.contains("משנה ברורה") || cleanPath.contains("mishnah") || cleanPath.contains("mb")) {
            return "תת_לשימוש_המאגר_המוכן_משנב"
        }
        if (cleanPath.contains("חפץ חיים") || cleanPath.contains("חפץ_חיים") || cleanPath.contains("חפץחיים") || cleanPath.contains("חפץ") || cleanPath.contains("hefetz") || cleanPath.contains("hc")) {
            return "תת_לשימוש_המאגר_המוכן_חפץ_חיים"
        }
        if (cleanPath.contains("ankidaf") || cleanPath.contains("דף") || cleanPath.contains("daf")) {
            return "AnkiDaf"
        }
        
        return "תת - שאלה רגילה - (לשימוש המאגר)"
    }

    /**
     * Extracts and pads tab-separated fields according to the selected model's dimensions.
     */
    fun getFieldsForModel(modelName: String, rawText: String): String {
        val fields = rawText.split("\t")
        val expectedSize = when (modelName) {
            "AnkiDaf" -> 5
            "Anki-Daf-cloze" -> 3
            "תת_לשימוש_המאגר_המוכן_חפץ_חיים" -> 4
            "תת_לשימוש_המאגר_המוכן_משנב" -> 4
            "תת - שאלה רגילה - (לשימוש המאגר)" -> 4
            else -> 2
        }
        
        val list = mutableListOf<String>()
        for (i in 0 until expectedSize) {
            val fieldVal = fields.getOrNull(i) ?: ""
            list.add(fieldVal)
        }
        return list.joinToString("\t")
    }
    
    /**
     * Direct API to add a single note into a deck.
     */
    fun addNote(context: Context, deckId: Long, modelId: Long, front: String, back: String, tags: String = ""): Uri? {
        val uri = Uri.withAppendedPath(getContentUri(context), "notes")
        
        // Join card fields using a tab character \t
        val flds = "$front\t$back"
        val values = ContentValues().apply {
            put("did", deckId)
            put("mid", modelId)
            put("flds", flds)
            if (tags.isNotEmpty()) {
                put("tags", tags)
            }
        }
        return try {
            context.contentResolver.insert(uri, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Overloaded API to add a single note into a deck with pre-assembled tabs.
     */
    fun addNoteWithFields(context: Context, deckId: Long, modelId: Long, flds: String, tags: String = ""): Uri? {
        val uri = Uri.withAppendedPath(getContentUri(context), "notes")
        val values = ContentValues().apply {
            put("did", deckId)
            put("mid", modelId)
            put("flds", flds)
            if (tags.isNotEmpty()) {
                put("tags", tags)
            }
        }
        return try {
            context.contentResolver.insert(uri, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
