package ru.lecturenotes.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.lecturenotes.app.AppContainer
import ru.lecturenotes.app.BuildConfig
import ru.lecturenotes.app.LectureNotesApp
import ru.lecturenotes.app.data.AppSettings
import ru.lecturenotes.app.data.SettingsRepository
import ru.lecturenotes.app.network.ApiClient
import ru.lecturenotes.app.network.UsageOut
import ru.lecturenotes.app.recording.RecorderState
import ru.lecturenotes.app.recording.RecordingService
import ru.lecturenotes.app.ui.appViewModel

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var form by mutableStateOf<AppSettings?>(null)
        private set
    var usage by mutableStateOf<UsageOut?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            form = c.settings.current()
            usage = runCatching { c.auth.usage() }.getOrNull()
        }
    }

    fun edit(transform: (AppSettings) -> AppSettings) {
        form = form?.let(transform)
        status = null
    }

    fun save(onDone: () -> Unit) {
        val f = form ?: return
        viewModelScope.launch {
            val before = c.settings.current().serverUrl
            c.settings.save(f)
            // Токены выданы конкретным сервером — при смене адреса нужно войти заново.
            if (c.settings.current().serverUrl != before) c.auth.logout()
            onDone()
        }
    }

    fun checkServer() {
        val f = form ?: return
        busy = true
        viewModelScope.launch {
            status = try {
                c.api.get(SettingsRepository.normalizeUrl(f.serverUrl)).auth.health()
                "Сервер доступен"
            } catch (e: Exception) {
                ApiClient.describe(e)
            }
            busy = false
        }
    }

    fun logout() {
        // Ключи нейросетей привязаны к человеку — при выходе удаляем их с телефона.
        c.appScope.launch {
            c.aiKeys.clear()
            c.auth.logout()
        }
    }

    fun deleteAccount(context: Context, password: String) {
        busy = true
        viewModelScope.launch {
            try {
                c.auth.deleteAccount(password)
            } catch (e: Exception) {
                status = ApiClient.describe(e)
                busy = false
                return@launch
            }
            // Очистка — в скоупе приложения: экран закроется, как только сессия исчезнет.
            c.appScope.launch {
                if (RecorderState.state.value.isRecording) RecordingService.stop(context.applicationContext)
                c.processor.cancelAll()
                c.repo.wipeAll()
                c.aiKeys.clear()
                c.auth.forgetSession()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAiKeys: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
    val session by (context.applicationContext as LectureNotesApp).container.tokens.session.collectAsState()
    val form = vm.form
    var confirmLogout by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        if (form == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Аккаунт", style = MaterialTheme.typography.titleMedium)
            Text(session?.email.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            vm.usage?.let { u ->
                Text(
                    "За последние сутки: аудио ${u.audioMbUsed} из ${u.audioMbLimit} МБ, " +
                        "коррекция ${u.correctCharsUsed} из ${u.correctCharsLimit} символов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmLogout = true }) { Text("Выйти") }
                TextButton(onClick = { deleting = true }) {
                    Text("Удалить аккаунт", color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()

            if (BuildConfig.ALLOW_SERVER_OVERRIDE) {
                Text("Сервер (только debug-сборка)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = form.serverUrl,
                    onValueChange = { v -> vm.edit { it.copy(serverUrl = v) } },
                    label = { Text("Адрес сервера") },
                    supportingText = { Text("Эмулятор: http://10.0.2.2:8000 · телефон: http://<IP компьютера>:8000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = vm::checkServer, enabled = !vm.busy) { Text("Проверить соединение") }
                HorizontalDivider()
            }
            vm.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text("Нейросети", style = MaterialTheme.typography.titleMedium)
            val aiKeys by (context.applicationContext as LectureNotesApp).container.aiKeys.keys.collectAsState()
            Text(
                listOfNotNull(
                    aiKeys.llm?.let { "коррекция — свой ключ (${it.title ?: it.provider})" },
                    aiKeys.stt?.let { "распознавание — свой ключ (${it.title ?: it.provider})" },
                ).joinToString("; ").ifBlank { "Используются нейросети сервера" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onAiKeys, modifier = Modifier.fillMaxWidth()) {
                Text("GigaChat, ChatGPT, Claude и другие: свои ключи")
            }
            HorizontalDivider()

            Text("Распознавание", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.language,
                onValueChange = { v -> vm.edit { it.copy(language = v.take(2)) } },
                label = { Text("Язык лекций (ru, en, de…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ИИ-коррекция текста")
                    Text(
                        "Исправлять ошибки распознавания, пунктуацию и разбивать на абзацы",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = form.autoCorrect, onCheckedChange = { v -> vm.edit { it.copy(autoCorrect = v) } })
            }

            Button(onClick = { vm.save(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
            Text(
                "Версия ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Конспекты останутся на телефоне. Новые записи не будут распознаваться, пока вы не войдёте снова.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }

    if (deleting) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Удалить аккаунт?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Аккаунт будет удалён с сервера, а все конспекты, фото и записи — с этого телефона. Это нельзя отменить.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { deleting = false; vm.deleteAccount(context, password) },
                    enabled = password.isNotEmpty() && !vm.busy,
                ) { Text("Удалить навсегда", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } },
        )
    }
}
