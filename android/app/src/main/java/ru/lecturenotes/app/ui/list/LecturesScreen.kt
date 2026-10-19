package ru.lecturenotes.app.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.lecturenotes.app.data.LectureEntity
import ru.lecturenotes.app.data.LectureRepository
import ru.lecturenotes.app.recording.RecorderState
import ru.lecturenotes.app.ui.appViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LecturesViewModel(private val repo: LectureRepository) : ViewModel() {
    val lectures: StateFlow<List<LectureEntity>> = repo.observeLectures()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun create(title: String, subject: String): Long = repo.createLecture(title, subject)

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteLecture(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LecturesScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel { LecturesViewModel(it.repo) }
    val lectures by vm.lectures.collectAsStateWithLifecycle()
    val rec by RecorderState.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<LectureEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мои лекции") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Настройки") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Новая лекция") },
            )
        },
    ) { padding ->
        if (lectures.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Filled.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(16.dp))
                    Text("Пока нет ни одной лекции", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Создайте лекцию, включите запись — текст появится сам. " +
                            "Фотографируйте слайды прямо во время записи.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lectures, key = { it.id }) { lecture ->
                    LectureCard(
                        lecture = lecture,
                        isRecording = rec.lectureId == lecture.id,
                        onClick = { onOpen(lecture.id) },
                        onDelete = { toDelete = lecture },
                    )
                }
            }
        }
    }

    if (showCreate) {
        LectureTitleDialog(
            title = "Новая лекция",
            confirmText = "Создать",
            onDismiss = { showCreate = false },
            onConfirm = { t, s ->
                showCreate = false
                scope.launch { onOpen(vm.create(t, s)) }
            },
        )
    }

    toDelete?.let { lecture ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить лекцию?") },
            text = { Text("«${lecture.title}» будет удалена вместе со всеми фото, видео и записями.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (rec.lectureId != lecture.id) vm.delete(lecture.id)
                        toDelete = null
                    },
                    enabled = rec.lectureId != lecture.id,
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun LectureCard(
    lecture: LectureEntity,
    isRecording: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = remember(lecture.updatedAt) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(lecture.updatedAt))
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    lecture.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lecture.subject.isNotBlank()) {
                    Text(
                        lecture.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isRecording) {
                Icon(Icons.Filled.FiberManualRecord, "Идёт запись", tint = MaterialTheme.colorScheme.error)
            } else {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Удалить") }
            }
        }
    }
}

@Composable
fun LectureTitleDialog(
    title: String,
    confirmText: String,
    initialTitle: String = "",
    initialSubject: String = "",
    onDismiss: () -> Unit,
    onConfirm: (title: String, subject: String) -> Unit,
) {
    var t by rememberSaveable { mutableStateOf(initialTitle) }
    var s by rememberSaveable { mutableStateOf(initialSubject) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = t, onValueChange = { t = it },
                    label = { Text("Тема лекции") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s, onValueChange = { s = it },
                    label = { Text("Предмет (необязательно)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Тема и предмет помогают ИИ правильно распознавать термины.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(t.ifBlank { "Лекция" }, s) }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
