package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

  @Test
  fun launchMainActivityWithMockSettings() {
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db = Room.databaseBuilder(
          context,
          AppDatabase::class.java,
          "anki_qa_database"
      ).allowMainThreadQueries().build()
      
      val dao = db.appDao()
      dao.saveSettings(AppSettings(
          id = 1,
          zipFileUri = "content://dummy/path.zip",
          zipFileName = "path.zip",
          repoJsonString = """
              {
                "name": "מאגר השאלות",
                "type": "directory",
                "children": [
                  {
                    "name": "קובץ 1.txt",
                    "type": "file"
                  }
                ]
              }
          """.trimIndent()
      ))
      db.close()
    }

    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assert(activity != null)
      }
    }
  }
}
