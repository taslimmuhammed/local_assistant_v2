package com.local.assistant.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.assistant.AppContainer
import com.local.assistant.data.prefs.SettingsStore
import com.local.assistant.memory.MemoryControls
import com.local.assistant.memory.core.UserProfile
import com.local.assistant.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val controls: MemoryControls,
    private val settings: SettingsStore,
) : ViewModel() {

    data class State(
        val values: Map<String, String> = emptyMap(),
        val loaded: Boolean = false,
        val saving: Boolean = false,
    ) {
        fun problem(field: UserProfile.Field): String? = UserProfile.problem(field, values[field.attribute].orEmpty())
        val valid: Boolean get() = UserProfile.FIELDS.none { problem(it) != null }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = controls.profile()
            _state.update { it.copy(values = stored + it.values, loaded = true) }
        }
    }

    fun update(field: UserProfile.Field, text: String) {
        val value = if (field.numeric) text.filter { it.isDigit() }.take(field.maxChars) else text.take(field.maxChars)
        _state.update { it.copy(values = it.values + (field.attribute to value)) }
    }

    /** Saves and marks the profile as asked; [onDone] runs once it is stored. */
    fun save(onDone: () -> Unit) {
        val current = _state.value
        if (!current.valid || current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            controls.saveProfile(current.values)
            settings.profileAsked = true
            _state.update { it.copy(saving = false) }
            onDone()
        }
    }

    fun skip() {
        settings.profileAsked = true
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(container.memoryControls, container.settings) }
        }
    }
}

/**
 * The user's profile: asked for once when the app is first opened ([onboarding]), and editable
 * afterwards from the menu. Everything is optional. What is filled in is kept in mind in every
 * chat, as part of the system prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onboarding: Boolean, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = AppColors.Background,
        topBar = {
            if (!onboarding) {
                TopAppBar(
                    title = { Text("Your profile", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppColors.Background,
                        titleContentColor = AppColors.TextPrimary,
                        navigationIconContentColor = AppColors.TextPrimary,
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (onboarding) {
                Text(
                    "Hi! Tell me a little about yourself",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
            Text(
                "I keep this in mind in every chat. It stays on this phone, everything is optional, " +
                    "and you can change it any time from Your profile in the menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = if (onboarding) 12.dp else 8.dp, bottom = 16.dp),
            )

            UserProfile.FIELDS.forEachIndexed { index, field ->
                val problem = state.problem(field)
                OutlinedTextField(
                    value = state.values[field.attribute].orEmpty(),
                    onValueChange = { viewModel.update(field, it) },
                    label = { Text(field.label) },
                    placeholder = { Text(field.hint) },
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    singleLine = true,
                    enabled = state.loaded,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text,
                        capitalization = if (field.numeric) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                        imeAction = if (index == UserProfile.FIELDS.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onboarding) {
                    TextButton(onClick = {
                        viewModel.skip()
                        onDone()
                    }) { Text("Skip for now") }
                }
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = state.loaded && state.valid && !state.saving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
                ) { Text(if (onboarding) "Continue" else "Save") }
            }
        }
    }
}
