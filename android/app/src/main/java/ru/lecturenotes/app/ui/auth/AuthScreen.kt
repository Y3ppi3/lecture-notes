package ru.lecturenotes.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.lecturenotes.app.auth.AuthRepository
import ru.lecturenotes.app.network.ApiClient
import ru.lecturenotes.app.ui.appViewModel

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submit(register: Boolean, email: String, password: String) {
        val e = email.trim()
        when {
            !EMAIL.matches(e) -> { error = "Введите корректный email"; return }
            password.length < MIN_PASSWORD -> { error = "Пароль — не короче $MIN_PASSWORD символов"; return }
            password.length > 128 -> { error = "Слишком длинный пароль"; return }
        }
        busy = true
        error = null
        viewModelScope.launch {
            try {
                if (register) auth.register(e, password) else auth.login(e, password)
            } catch (ex: Exception) {
                error = ApiClient.describe(ex).let {
                    if (!register && it.startsWith("Сессия")) "Неверный email или пароль" else it
                }
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        const val MIN_PASSWORD = 8
    }
}

@Composable
fun AuthScreen() {
    val vm = appViewModel { AuthViewModel(it.auth) }
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Пароль намеренно не сохраняется в Bundle (rememberSaveable) — только в памяти.
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Filled.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (register) "Регистрация" else "Вход", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Аккаунт нужен для распознавания речи и ИИ-коррекции. Конспекты хранятся только на телефоне.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (showPassword) "Скрыть пароль" else "Показать пароль",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!vm.busy) vm.submit(register, email, password) }),
                supportingText = { if (register) Text("Не короче 8 символов") },
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Button(
                onClick = { vm.submit(register, email, password) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (register) "Создать аккаунт" else "Войти")
                }
            }
            TextButton(onClick = { register = !register }) {
                Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
            }
        }
    }
}
