package ru.lecturenotes.app.ui.lecture

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.lecturenotes.app.data.BlockEntity
import ru.lecturenotes.app.data.BlockType
import ru.lecturenotes.app.recording.RecState
import ru.lecturenotes.app.recording.RecorderState
import ru.lecturenotes.app.ui.appViewModelWithState
import ru.lecturenotes.app.ui.list.LectureTitleDialog

/** Что сейчас редактируется в диалоге текста. */
private data class TextEdit(val block: BlockEntity? = null, val after: BlockEntity? = null, val initial: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LectureScreen(lectureId: Long, onBack: () -> Unit, onSettings: () -> Unit) {
    val vm = appViewModelWithState(key = "lecture-$lectureId") { c, saved -> LectureViewModel(c, saved, lectureId) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lecture by vm.lecture.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val rec by RecorderState.state.collectAsState()
    val recordingHere = rec.lectureId == lectureId

    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // ---------- состояние диалогов ----------
    var textEdit by remember { mutableStateOf<TextEdit?>(null) }
    var captionEdit by remember { mutableStateOf<BlockEntity?>(null) }
    var originalOf by remember { mutableStateOf<BlockEntity?>(null) }
    var imageToView by remember { mutableStateOf<String?>(null) }
    var videoToPlay by remember { mutableStateOf<String?>(null) }
    var showVideoLink by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDeleteLecture by rememberSaveable { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var videoMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(rec.error) {
        rec.error?.let {
            snackbar.showSnackbar(it)
            RecorderState.clearError()
        }
    }
    // Во время записи держим в поле зрения последний блок.
    LaunchedEffect(blocks.size, recordingHere) {
        if (recordingHere && blocks.isNotEmpty()) listState.animateScrollToItem(blocks.lastIndex)
    }

    // ---------- системные экраны ----------
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            vm.startRecording(context)
        } else {
            vm.showMessage("Без доступа к микрофону запись невозможна — разрешите его в настройках телефона")
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onCaptured(context, ok, BlockType.IMAGE)
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        vm.onCaptured(context, ok, BlockType.VIDEO)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        vm.onPicked(context, uris, BlockType.IMAGE)
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        vm.onPicked(context, listOfNotNull(uri), BlockType.VIDEO)
    }

    fun startRecording() {
        if (!vm.canStartRecording()) return
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.startRecording(context) else permissions.launch(missing.toTypedArray())
    }

    fun launchSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: ActivityNotFoundException) {
            vm.showMessage("На телефоне не найдено приложение камеры")
        }
    }

    val actions = remember(vm) {
        object : BlockActions {
            override fun edit(block: BlockEntity) { textEdit = TextEdit(block = block, initial = block.text) }
            override fun correctWithAi(block: BlockEntity) = vm.correctWithAi(block)
            override fun showOriginal(block: BlockEntity) { originalOf = block }
            override fun insertTextAfter(block: BlockEntity) { textEdit = TextEdit(after = block) }
            override fun editCaption(block: BlockEntity) { captionEdit = block }
            override fun moveUp(block: BlockEntity) = vm.move(block, -1)
            override fun moveDown(block: BlockEntity) = vm.move(block, +1)
            override fun delete(block: BlockEntity) = vm.delete(block)
            override fun retry(block: BlockEntity) = vm.retry(block)
            override fun openImage(block: BlockEntity) { imageToView = block.mediaPath }
            override fun openVideo(block: BlockEntity) { videoToPlay = block.mediaPath }
            override fun openLink(block: BlockEntity) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(block.text)))
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(lecture?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        lecture?.subject?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = {
                        lecture?.let { Export.share(context, it, blocks, vm::uriFor) }
                    }) { Icon(Icons.Filled.Share, "Поделиться") }
                    Box {
                        IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { topMenu = false; showRename = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Копировать текст") },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                onClick = {
                                    topMenu = false
                                    lecture?.let { clipboard.setText(AnnotatedString(Export.toMarkdown(it, blocks))) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Настройки") },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { topMenu = false; onSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить лекцию") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { topMenu = false; showDeleteLecture = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { launchSafely { takePhoto.launch(vm.newPhotoUri()) } }) {
                        Icon(Icons.Filled.PhotoCamera, "Сфотографировать слайд")
                    }
                    IconButton(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Filled.AddPhotoAlternate, "Фото из галереи") }
                    Box {
                        IconButton(onClick = { videoMenu = true }) { Icon(Icons.Filled.Videocam, "Добавить видео") }
                        DropdownMenu(expanded = videoMenu, onDismissRequest = { videoMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Снять видео") },
                                leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                                onClick = {
                                    videoMenu = false
                                    launchSafely { captureVideo.launch(vm.newVideoUri()) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Видео из галереи") },
                                leadingIcon = { Icon(Icons.Filled.VideoLibrary, null) },
                                onClick = {
                                    videoMenu = false
                                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Ссылка на видео") },
                                leadingIcon = { Icon(Icons.Filled.Link, null) },
                                onClick = { videoMenu = false; showVideoLink = true },
                            )
                        }
                    }
                    IconButton(onClick = { textEdit = TextEdit() }) { Icon(Icons.Filled.Notes, "Добавить текст") }
                    Spacer(Modifier.weight(1f))
                    RecordControls(
                        rec = rec,
                        recordingHere = recordingHere,
                        onStart = { startRecording() },
                        onPause = { vm.pauseRecording(context) },
                        onResume = { vm.resumeRecording(context) },
                        onStop = { vm.stopRecording(context) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (recordingHere) RecordingBanner(rec)
            if (blocks.isEmpty()) {
                EmptyLecture(Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(blocks, key = { _, b -> b.id }) { index, block ->
                        BlockItem(
                            block = block,
                            isFirst = index == 0,
                            isLast = index == blocks.lastIndex,
                            isCorrecting = vm.correctingBlockId == block.id,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }

    // ---------- диалоги ----------

    textEdit?.let { edit ->
        TextEditDialog(
            title = if (edit.block != null) "Редактирование" else "Новый текст",
            initial = edit.initial,
            onDismiss = { textEdit = null },
            onSave = { text ->
                textEdit = null
                if (edit.block != null) vm.updateText(edit.block, text) else vm.addText(text, edit.after)
            },
        )
    }

    captionEdit?.let { block ->
        TextEditDialog(
            title = "Подпись",
            initial = block.caption,
            singleLine = true,
            onDismiss = { captionEdit = null },
            onSave = { captionEdit = null; vm.updateCaption(block, it) },
        )
    }

    originalOf?.let { block ->
        AlertDialog(
            onDismissRequest = { originalOf = null },
            title = { Text("Исходный текст") },
            text = {
                Box(Modifier.heightIn(max = 400.dp)) {
                    LazyColumn { item { Text(block.rawText.orEmpty()) } }
                }
            },
            confirmButton = {
                TextButton(onClick = { originalOf = null; vm.restoreOriginal(block) }) { Text("Вернуть этот вариант") }
            },
            dismissButton = { TextButton(onClick = { originalOf = null }) { Text("Закрыть") } },
        )
    }

    if (showVideoLink) VideoLinkDialog(
        onDismiss = { showVideoLink = false },
        onSave = { url, caption -> showVideoLink = false; vm.addVideoLink(url, caption) },
    )

    if (showRename) lecture?.let { l ->
        LectureTitleDialog(
            title = "Лекция",
            confirmText = "Сохранить",
            initialTitle = l.title,
            initialSubject = l.subject,
            onDismiss = { showRename = false },
            onConfirm = { t, s -> showRename = false; vm.rename(t, s) },
        )
    }

    if (showDeleteLecture) AlertDialog(
        onDismissRequest = { showDeleteLecture = false },
        title = { Text("Удалить лекцию?") },
        text = { Text("Лекция будет удалена вместе со всеми фото, видео и записями.") },
        confirmButton = {
            TextButton(onClick = { showDeleteLecture = false; vm.deleteLecture(onBack) }) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = { showDeleteLecture = false }) { Text("Отмена") } },
    )

    imageToView?.let { ImageViewerDialog(it) { imageToView = null } }
    videoToPlay?.let { VideoPlayerDialog(it) { videoToPlay = null } }
}

@Composable
private fun RecordControls(
    rec: RecState,
    recordingHere: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    if (!recordingHere) {
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Запись")
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = if (rec.isPaused) onResume else onPause) {
                Icon(
                    if (rec.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    if (rec.isPaused) "Продолжить" else "Пауза",
                )
            }
            FilledIconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Остановить") }
        }
    }
}

@Composable
private fun RecordingBanner(rec: RecState) {
    val level by animateFloatAsState(rec.level, label = "level")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Mic, null,
                tint = if (rec.isPaused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                (if (rec.isPaused) "Пауза · " else "Запись · ") + formatDuration(rec.elapsedMs),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Фото встанет в текст по времени",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(6.dp))
        LinearProgressIndicator(
            progress = { if (rec.isPaused) 0f else level },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyLecture(modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Лекция пока пустая", style = MaterialTheme.typography.titleMedium)
            Text(
                "Нажмите «Запись» — речь будет расшифрована и исправлена ИИ каждые 3 минуты. " +
                    "Фото слайдов, видео и свои заметки добавляются кнопками внизу.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    singleLine: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun VideoLinkDialog(onDismiss: () -> Unit, onSave: (url: String, caption: String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var caption by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ссылка на видео") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("https://…") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = caption, onValueChange = { caption = it },
                    label = { Text("Подпись (необязательно)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, caption) }, enabled = url.isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
