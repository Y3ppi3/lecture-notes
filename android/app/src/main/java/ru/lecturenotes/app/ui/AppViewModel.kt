package ru.lecturenotes.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.lecturenotes.app.AppContainer
import ru.lecturenotes.app.LectureNotesApp

/** viewModel() с доступом к [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as LectureNotesApp
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(app.container) } },
    )
}

/** То же, но с [SavedStateHandle] — состояние переживает выгрузку процесса системой. */
@Composable
inline fun <reified VM : ViewModel> appViewModelWithState(
    key: String? = null,
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as LectureNotesApp
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(app.container, createSavedStateHandle()) } },
    )
}
