package me.rerere.rikkahub.ui.pages.novel

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.novel.NovelMeta
import me.rerere.rikkahub.data.novel.NovelStore

data class ReaderState(
    val chapter: Int = 0,
    val title: String = "",
    val body: String = "",
    val offset: Float = 0f,
    val loading: Boolean = true,
    val missing: Boolean = false,
)

class NovelReaderVM(
    private val novelId: String,
    private val store: NovelStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _novel = MutableStateFlow<NovelMeta?>(null)
    val novel: StateFlow<NovelMeta?> = _novel.asStateFlow()

    private val _typeVersion = MutableStateFlow(0)
    val typeVersion: StateFlow<Int> = _typeVersion.asStateFlow()

    var fontSize: Int = 18
        private set
    var lineHeight: Float = 1.8f
        private set

    init {
        viewModelScope.launch {
            val meta = store.find(novelId)
            _novel.value = meta
            if (meta == null) {
                _state.value = ReaderState(loading = false, missing = true)
                return@launch
            }
            load(meta.progressChapter, meta.progressOffset)
        }
    }

    private suspend fun load(ordinal: Int, offset: Float) {
        _state.value = _state.value.copy(chapter = ordinal, loading = true, offset = offset)
        val (title, body) = store.readChapter(novelId, ordinal)
        _state.value = ReaderState(
            chapter = ordinal,
            title = title,
            body = body,
            offset = offset,
            loading = false,
        )
        store.saveProgress(novelId, ordinal, offset)
    }

    fun open(ordinal: Int) {
        viewModelScope.launch { load(ordinal, 0f) }
    }

    fun prev() {
        val current = _state.value.chapter
        if (current > 0) open(current - 1)
    }

    fun next() {
        val total = _novel.value?.chapterCount ?: return
        val current = _state.value.chapter
        if (current + 1 < total) open(current + 1)
    }

    fun saveOffset(offset: Float) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(offset = offset)
        viewModelScope.launch { store.saveProgress(novelId, _state.value.chapter, offset) }
    }

    fun changeFontSize(value: Int) {
        fontSize = value.coerceIn(14, 26)
        _typeVersion.value++
    }

    fun changeLineHeight(value: Float) {
        lineHeight = value.coerceIn(1.3f, 2.4f)
        _typeVersion.value++
    }
}