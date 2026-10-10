package com.geoint.android

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.geoint.core.AgentEvent
import com.geoint.core.AgentException
import com.geoint.core.AgentOutcome
import com.geoint.core.AgentSettings
import com.geoint.core.ClaudeModel
import com.geoint.core.GeoAgent
import com.geoint.core.Mode
import com.geoint.core.PhotoInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppSettings(
    val apiKey: String = "",
    val model: ClaudeModel = ClaudeModel.OPUS,
    val effort: String = "high",
    val webFetch: Boolean = true,
    val tiles: Boolean = true,
    val language: String = LANGUAGES.first(),
) {
    companion object {
        val LANGUAGES = listOf("Moroccan Darija (Arabic script)", "Arabic", "French", "English")
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

data class UiState(
    val settings: AppSettings = AppSettings(),
    val photoUri: Uri? = null,
    val photo: LoadedPhoto? = null,
    val loadingPhoto: Boolean = false,
    val mode: Mode = Mode.QUICK,
    val note: String = "",
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val thinking: String = "",
    val liveText: String = "",
    val outcome: AgentOutcome? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    // Private app storage; the key never leaves the device except in requests to api.anthropic.com.
    private val prefs = app.getSharedPreferences("geoint", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(settings = readSettings()))
    val state: StateFlow<UiState> = _state

    private var job: Job? = null
    private var agent: GeoAgent? = null

    private fun readSettings() = AppSettings(
        apiKey = prefs.getString("api_key", "") ?: "",
        model = ClaudeModel.fromId(prefs.getString("model", null)),
        effort = prefs.getString("effort", "high") ?: "high",
        webFetch = prefs.getBoolean("web_fetch", true),
        tiles = prefs.getBoolean("tiles", true),
        language = prefs.getString("language", null) ?: AppSettings.LANGUAGES.first(),
    )

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putString("api_key", s.apiKey.trim())
            .putString("model", s.model.id)
            .putString("effort", s.effort)
            .putBoolean("web_fetch", s.webFetch)
            .putBoolean("tiles", s.tiles)
            .putString("language", s.language)
            .apply()
        val tilesChanged = s.tiles != _state.value.settings.tiles
        _state.update { it.copy(settings = s.copy(apiKey = s.apiKey.trim())) }
        if (tilesChanged) _state.value.photoUri?.let(::loadPhoto)
    }

    fun setMode(mode: Mode) = _state.update { it.copy(mode = mode) }
    fun setNote(note: String) = _state.update { it.copy(note = note) }

    fun loadPhoto(uri: Uri) {
        if (_state.value.running) return
        _state.update { it.copy(photoUri = uri, photo = null, loadingPhoto = true, outcome = null, error = null, log = emptyList(), liveText = "") }
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { PhotoLoader.load(getApplication<Application>(), uri, _state.value.settings.tiles) }
                _state.update { it.copy(photo = photo, loadingPhoto = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingPhoto = false, error = "Could not open the photo: ${e.message}") }
            }
        }
    }

    fun analyze() {
        val s = _state.value
        val photo = s.photo ?: return
        if (s.running) return
        if (s.settings.apiKey.isBlank()) {
            _state.update { it.copy(error = "Add your Anthropic API key in Settings first.") }
            return
        }
        val geoAgent = GeoAgent(
            AgentSettings(
                apiKey = s.settings.apiKey,
                model = s.settings.model,
                effort = s.settings.effort,
                mode = s.mode,
                webFetch = s.settings.webFetch,
                answerLanguage = s.settings.language,
            )
        )
        agent = geoAgent
        _state.update { it.copy(running = true, outcome = null, error = null, log = emptyList(), thinking = "", liveText = "") }
        job = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    geoAgent.run(PhotoInput(photo.images, photo.metadata, s.note), ::onEvent)
                }
                _state.update { it.copy(running = false, outcome = outcome, liveText = "", thinking = "") }
            } catch (e: Throwable) {
                _state.update { it.copy(running = false, error = describeError(e)) }
            } finally {
                agent = null
            }
        }
    }

    fun cancel() {
        agent?.cancel()
        job?.cancel()
        _state.update { it.copy(running = false, error = "Cancelled") }
    }

    private fun onEvent(event: AgentEvent) {
        _state.update { st ->
            when (event) {
                is AgentEvent.Status -> st.copy(log = st.log + "• " + event.text)
                is AgentEvent.WebSearch -> st.copy(log = st.log + "🔎 " + event.query)
                is AgentEvent.WebFetch -> st.copy(log = st.log + "🌐 " + event.url)
                is AgentEvent.Library -> st.copy(log = st.log + "📚 GeoInt clue library: " + event.region)
                is AgentEvent.Thinking -> st.copy(thinking = (st.thinking + event.delta).takeLast(600))
                is AgentEvent.Text -> st.copy(liveText = st.liveText + event.delta)
                AgentEvent.TurnStart -> st.copy(liveText = "")
            }
        }
    }

    private fun describeError(e: Throwable): String = when (e) {
        is AgentException -> e.message ?: "Failed"
        is UnauthorizedException -> "The API key was rejected (401). Check it in Settings."
        is RateLimitException -> "Rate limited by the Claude API (429). Wait a minute and retry."
        is AnthropicIoException -> "Network error: ${e.message}"
        is AnthropicServiceException -> "Claude API error ${e.statusCode()}: ${e.message}"
        else -> "Failed: ${e.javaClass.simpleName}: ${e.message}"
    }
}
