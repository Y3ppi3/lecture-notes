package ru.lecturenotes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.lecturenotes.app.ui.ai.AiKeysScreen
import ru.lecturenotes.app.ui.auth.AuthScreen
import ru.lecturenotes.app.ui.lecture.LectureScreen
import ru.lecturenotes.app.ui.list.LecturesScreen
import ru.lecturenotes.app.ui.settings.SettingsScreen
import ru.lecturenotes.app.ui.theme.LectureNotesTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LECTURE_ID = "open_lecture_id"
    }

    /** Лекция, которую нужно открыть (из уведомления о записи). */
    private val pendingLectureId = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        val tokens = (application as LectureNotesApp).container.tokens

        setContent {
            LectureNotesTheme {
                val loaded by tokens.loaded.collectAsState()
                val session by tokens.session.collectAsState()
                when {
                    !loaded -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    session == null -> AuthScreen()
                    else -> AppNavigation()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AppNavigation() {
        val nav = rememberNavController()
        val openId = pendingLectureId.longValue
        LaunchedEffect(openId) {
            if (openId > 0) {
                nav.navigate("lecture/$openId") { launchSingleTop = true }
                pendingLectureId.longValue = -1L
            }
        }

        NavHost(navController = nav, startDestination = "lectures") {
            composable("lectures") {
                LecturesScreen(
                    onOpen = { id -> nav.navigate("lecture/$id") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable(
                "lecture/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                LectureScreen(
                    lectureId = entry.arguments?.getLong("id") ?: -1L,
                    onBack = { nav.popBackStack() },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() }, onAiKeys = { nav.navigate("ai") })
            }
            composable("ai") {
                AiKeysScreen(onBack = { nav.popBackStack() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_LECTURE_ID, -1L) ?: -1L
        if (id > 0) pendingLectureId.longValue = id
    }
}
