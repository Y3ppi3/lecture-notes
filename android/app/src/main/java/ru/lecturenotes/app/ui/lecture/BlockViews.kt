package ru.lecturenotes.app.ui.lecture

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.lecturenotes.app.data.BlockEntity
import ru.lecturenotes.app.data.BlockStatus
import ru.lecturenotes.app.data.BlockType
import java.io.File

/** Действия, доступные над блоком. Реализует экран лекции. */
interface BlockActions {
    fun edit(block: BlockEntity)
    fun correctWithAi(block: BlockEntity)
    fun showOriginal(block: BlockEntity)
    fun insertTextAfter(block: BlockEntity)
    fun editCaption(block: BlockEntity)
    fun moveUp(block: BlockEntity)
    fun moveDown(block: BlockEntity)
    fun delete(block: BlockEntity)
    fun retry(block: BlockEntity)
    fun openImage(block: BlockEntity)
    fun openVideo(block: BlockEntity)
    fun openLink(block: BlockEntity)
}

@Composable
fun BlockItem(
    block: BlockEntity,
    isFirst: Boolean,
    isLast: Boolean,
    isCorrecting: Boolean,
    actions: BlockActions,
) {
    when (block.type) {
        BlockType.TEXT -> when (block.status) {
            BlockStatus.RECORDING -> RecordingBlock()
            BlockStatus.PROCESSING -> ProcessingBlock(if (block.audioPath != null && block.rawText == null) {
                "Распознаю речь и исправляю текст…"
            } else {
                "ИИ исправляет текст…"
            })
            BlockStatus.ERROR -> ErrorBlock(block, actions)
            BlockStatus.READY -> TextBlock(block, isFirst, isLast, isCorrecting, actions)
        }
        BlockType.IMAGE -> MediaCard(block, isFirst, isLast, actions) {
            AsyncImage(
                model = block.mediaPath?.let(::File),
                contentDescription = block.caption.ifBlank { "Фото слайда" },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .clickable { actions.openImage(block) },
            )
        }
        BlockType.VIDEO -> MediaCard(block, isFirst, isLast, actions) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color.Black)
                    .clickable { actions.openVideo(block) },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = block.mediaPath?.let(::File),
                    contentDescription = "Видео",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, "Смотреть", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
        BlockType.VIDEO_LINK -> MediaCard(block, isFirst, isLast, actions) {
            Row(
                Modifier.fillMaxWidth().clickable { actions.openLink(block) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Видео по ссылке", style = MaterialTheme.typography.labelMedium)
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextBlock(
    block: BlockEntity,
    isFirst: Boolean,
    isLast: Boolean,
    isCorrecting: Boolean,
    actions: BlockActions,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { actions.edit(block) }.padding(end = 40.dp, top = 4.dp, bottom = 4.dp)) {
            MarkdownText(block.text)
            if (isCorrecting) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        BlockMenu(
            modifier = Modifier.align(Alignment.TopEnd),
            block = block, isFirst = isFirst, isLast = isLast, actions = actions,
        ) { close ->
            MenuItem("Редактировать", Icons.Filled.Edit) { close(); actions.edit(block) }
            MenuItem("Исправить с ИИ", Icons.Filled.AutoFixHigh) { close(); actions.correctWithAi(block) }
            if (block.rawText != null && block.rawText != block.text) {
                MenuItem("Исходный текст", Icons.Filled.History) { close(); actions.showOriginal(block) }
            }
            MenuItem("Вставить текст ниже", Icons.Filled.PlaylistAdd) { close(); actions.insertTextAfter(block) }
        }
    }
}

@Composable
private fun MediaCard(
    block: BlockEntity,
    isFirst: Boolean,
    isLast: Boolean,
    actions: BlockActions,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Box {
            content()
            BlockMenu(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                block = block, isFirst = isFirst, isLast = isLast, actions = actions,
            ) { close ->
                MenuItem("Подпись", Icons.Filled.ShortText) { close(); actions.editCaption(block) }
                MenuItem("Вставить текст ниже", Icons.Filled.PlaylistAdd) { close(); actions.insertTextAfter(block) }
            }
        }
        if (block.caption.isNotBlank()) {
            Text(
                block.caption,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BlockMenu(
    modifier: Modifier,
    block: BlockEntity,
    isFirst: Boolean,
    isLast: Boolean,
    actions: BlockActions,
    extraItems: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Действия") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            extraItems(close)
            HorizontalDivider()
            if (!isFirst) MenuItem("Переместить выше", Icons.Filled.ArrowUpward) { close(); actions.moveUp(block) }
            if (!isLast) MenuItem("Переместить ниже", Icons.Filled.ArrowDownward) { close(); actions.moveDown(block) }
            MenuItem("Удалить", Icons.Outlined.Delete) { close(); actions.delete(block) }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun RecordingBlock() {
    val transition = rememberInfiniteTransition(label = "rec")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    StatusRow {
        Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.alpha(alpha))
        Spacer(Modifier.width(12.dp))
        Text("Идёт запись фрагмента…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProcessingBlock(label: String) {
    StatusRow {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorBlock(block: BlockEntity, actions: BlockActions) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Фрагмент не обработан",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            block.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.retry(block) }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Повторить")
                }
                TextButton(onClick = { actions.delete(block) }) { Text("Удалить") }
            }
        }
    }
}

@Composable
private fun StatusRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
