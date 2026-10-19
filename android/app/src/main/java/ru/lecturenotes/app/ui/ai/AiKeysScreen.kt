package ru.lecturenotes.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.lecturenotes.app.AppContainer
import ru.lecturenotes.app.ai.AiKeysRepository.Companion.KIND_LLM
import ru.lecturenotes.app.ai.AiKeysRepository.Companion.KIND_STT
import ru.lecturenotes.app.network.AiKey
import ru.lecturenotes.app.network.ApiClient
import ru.lecturenotes.app.network.CheckKeyRequest
import ru.lecturenotes.app.network.ProviderInfo
import ru.lecturenotes.app.network.ProvidersResponse
import ru.lecturenotes.app.ui.appViewModel

/** Черновик формы ключа для одного вида нейросети (коррекция или распознавание). */
data class KeyDraft(
    val provider: String = AUTO,
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val scope: String = "",
) {
    companion object {
        const val AUTO = "auto"
    }
}

class AiKeysViewModel(private val c: AppContainer) : ViewModel() {
    var catalog by mutableStateOf<ProvidersResponse?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    val drafts = mutableStateMapOf(KIND_LLM to KeyDraft(), KIND_STT to KeyDraft())
    val status = mutableStateMapOf<String, String>()
    val busy = mutableStateMapOf<String, Boolean>()
    val saved = c.aiKeys.keys

    init {
        load()
        viewModelScope.launch { c.aiKeys.load() }
    }

    fun load() {
        loadError = null
        viewModelScope.launch {
            catalog = try {
                c.api.get(c.settings.current().serverUrl).lecture.aiProviders()
            } catch (e: Exception) {
                loadError = ApiClient.describe(e)
                null
            }
        }
    }

    fun edit(kind: String, transform: (KeyDraft) -> KeyDraft) {
        drafts[kind] = transform(drafts[kind] ?: KeyDraft())
        status.remove(kind)
    }

    /** Проверяет ключ на сервере и, если он рабочий, сохраняет его на телефоне. */
    fun checkAndSave(kind: String) {
        val d = drafts[kind] ?: return
        if (d.apiKey.isBlank()) {
            status[kind] = "Вставьте ключ"
            return
        }
        val key = AiKey(
            provider = d.provider,
            apiKey = d.apiKey.trim(),
            model = d.model.trim().ifBlank { null },
            baseUrl = d.baseUrl.trim().ifBlank { null },
            scope = d.scope.ifBlank { null },
        )
        busy[kind] = true
        status.remove(kind)
        viewModelScope.launch {
            try {
                val resp = c.api.get(c.settings.current().serverUrl).lecture.checkKey(CheckKeyRequest(kind, key))
                if (resp.ok && resp.provider != null) {
                    c.aiKeys.set(kind, key.copy(provider = resp.provider, title = resp.title))
                    drafts[kind] = KeyDraft()
                    status[kind] = "Ключ подключён: ${resp.title}"
                } else {
                    status[kind] = resp.message
                }
            } catch (e: Exception) {
                status[kind] = ApiClient.describe(e)
            } finally {
                busy[kind] = false
            }
        }
    }

    fun remove(kind: String) {
        viewModelScope.launch {
            c.aiKeys.set(kind, null)
            status[kind] = "Ключ удалён с телефона"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiKeysScreen(onBack: () -> Unit) {
    val vm = appViewModel { AiKeysViewModel(it) }
    val saved by vm.saved.collectAsState()
    val catalog = vm.catalog

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Нейросети") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Можно пользоваться нейросетями сервера или подключить свой ключ: GigaChat, ChatGPT, Claude, " +
                    "Gemini, DeepSeek и другие. Ключ хранится только на этом телефоне в зашифрованном виде " +
                    "и передаётся серверу лишь вместе с запросом.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (catalog == null) {
                val error = vm.loadError
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::load) { Text("Повторить") }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (!catalog.allowUserKeys) {
                Text("Сервер не принимает собственные ключи — используются нейросети сервера.")
            } else {
                run {
                    KindSection(
                        vm = vm,
                        kind = KIND_LLM,
                        title = "Коррекция текста",
                        providers = catalog.llm,
                        serverTitle = catalog.server.llm.title.takeIf { catalog.server.llm.available },
                        saved = saved.llm,
                    )
                    KindSection(
                        vm = vm,
                        kind = KIND_STT,
                        title = "Распознавание речи",
                        providers = catalog.stt,
                        serverTitle = catalog.server.stt.title.takeIf { catalog.server.stt.available },
                        saved = saved.stt,
                    )
                }
            }
        }
    }
}

@Composable
private fun KindSection(
    vm: AiKeysViewModel,
    kind: String,
    title: String,
    providers: List<ProviderInfo>,
    serverTitle: String?,
    saved: AiKey?,
) {
    var ownKey by remember(saved == null) { mutableStateOf(saved != null || serverTitle == null) }
    val draft = vm.drafts[kind] ?: KeyDraft()
    val selected = providers.firstOrNull { it.id == draft.provider }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            if (serverTitle != null) {
                ChoiceRow("Нейросеть сервера: $serverTitle", selected = !ownKey) {
                    ownKey = false
                    if (saved != null) vm.remove(kind)
                }
            }
            ChoiceRow("Свой ключ", selected = ownKey) { ownKey = true }

            if (ownKey) {
                if (saved != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(saved.title ?: saved.provider, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Ключ ••••${saved.apiKey.takeLast(4)}" + (saved.model?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.remove(kind) }) { Text("Удалить") }
                    }
                    Text("Чтобы заменить ключ, заполните поля ниже.", style = MaterialTheme.typography.bodySmall)
                }

                ProviderPicker(providers, draft.provider) { id ->
                    vm.edit(kind) { it.copy(provider = id, scope = "", model = "") }
                }
                KeyField(draft.apiKey, selected?.keyHint ?: "Сервис определится по формату ключа") { v ->
                    vm.edit(kind) { it.copy(apiKey = v) }
                }
                if (selected != null && selected.customBaseUrl) {
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { v -> vm.edit(kind) { it.copy(baseUrl = v) } },
                        label = { Text("Адрес API (https://…/v1)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val scopes = selected?.scopes.orEmpty()
                if (scopes.isNotEmpty()) {
                    Text("Тип доступа", style = MaterialTheme.typography.labelLarge)
                    val current = draft.scope.ifBlank { scopes.first() }
                    scopes.forEach { scope ->
                        ChoiceRow(scopeLabel(scope), selected = current == scope) {
                            vm.edit(kind) { it.copy(scope = scope) }
                        }
                    }
                }
                if (kind == KIND_LLM || (selected != null && selected.customBaseUrl)) {
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { v -> vm.edit(kind) { it.copy(model = v) } },
                        label = { Text("Модель (необязательно)") },
                        placeholder = { Text(selected?.defaultModel?.ifBlank { null } ?: "по умолчанию") },
                        supportingText = {
                            val models = selected?.models.orEmpty()
                            if (models.isNotEmpty()) Text("Например: " + models.joinToString(", "))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = { vm.checkAndSave(kind) },
                    enabled = vm.busy[kind] != true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.busy[kind] == true) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Проверить и сохранить")
                    }
                }
            }
            vm.status[kind]?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProviderPicker(providers: List<ProviderInfo>, selectedId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = providers.firstOrNull { it.id == selectedId }?.title ?: "Определить по ключу"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Сервис: $label", Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Определить по ключу") }, onClick = { open = false; onSelect(KeyDraft.AUTO) })
            providers.forEach { p ->
                DropdownMenuItem(text = { Text(p.title) }, onClick = { open = false; onSelect(p.id) })
            }
        }
    }
}

@Composable
private fun KeyField(value: String, hint: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Ключ API") },
        supportingText = { Text(hint) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (visible) "Скрыть ключ" else "Показать ключ",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun scopeLabel(scope: String): String = when {
    scope.endsWith("_PERS") -> "Физическое лицо"
    scope.endsWith("_B2B") -> "ИП или юрлицо (пакеты)"
    scope.endsWith("_CORP") -> "ИП или юрлицо (оплата по факту)"
    else -> scope
}
