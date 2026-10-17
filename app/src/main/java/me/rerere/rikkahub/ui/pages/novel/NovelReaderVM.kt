package me.rerere.rikkahub.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.NovelChapterEntity
import me.rerere.rikkahub.data.db.entity.NovelEntity
import me.rerere.rikkahub.data.repository.NovelRepository

data class ReadingState(
    val chapter: Int = 0,
    val title: String = "",
    val offset: Float = 0f,
    val loading: Boolean = false,
)

class NovelReaderVM(
    private val novelId: String,
    private val repository: NovelRepository,
    private val preferences: NovelTypePreferences,
) : ViewModel() {

    val novel: StateFlow<NovelEntity?> = repository.getNovel(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<NovelChapterEntity>> = repository.listChapters(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val body = MutableStateFlow<String?>(null)
    val reading = MutableStateFlow(ReadingState())

    var fontSize: Int
        get() = preferences.fontSize
        private set(value) { preferences.fontSize = value }
    var lineHeight: Float
        get() = preferences.lineHeight
        private set(value) { preferences.lineHeight = value }

    private var initialised = false

    init {
        viewModelScope.launch {
            val saved = repository.getNovel(novelId).firstOrNullValue()
            val start = saved?.progressChapter ?: 0
            openChapter(start, saved?.progressOffset ?: 0f)
            initialised = true
        }
    }

    fun openChapter(ordinal: Int, offset: Float = 0f) {
        viewModelScope.launch {
            val current = novel.value ?: repository.getNovel(novelId).firstOrNullValue() ?: return@launch
            reading.value = reading.value.copy(chapter = ordinal, loading = true, offset = offset)
            val chapter = chapters.value.getOrNull(ordinal)
                ?: repository.getChapter(novelId, ordinal)
            val text = repository.readChapter(current, ordinal)
            reading.value = ReadingState(
                chapter = ordinal,
                title = chapter?.title ?: "第 ${ordinal + 1} 章",
                offset = offset,
                loading = false,
            )
            body.value = text
            repository.saveProgress(novelId, ordinal, offset)
        }
    }

    fun prevChapter() {
        if (reading.value.chapter > 0) openChapter(reading.value.chapter - 1)
    }

    fun nextChapter() {
        openChapter(reading.value.chapter + 1)
    }

    fun saveOffset(offset: Float) {
        if (!initialised) return
        reading.value = reading.value.copy(offset = offset)
        viewModelScope.launch {
            repository.saveProgress(novelId, reading.value.chapter, offset)
        }
    }

    fun setFontSize(value: Int) {
        preferences.fontSize = value.coerceIn(14, 26)
    }

    fun setLineHeight(value: Float) {
        preferences.lineHeight = value.coerceIn(1.3f, 2.4f)
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<NovelEntity?>.firstOrNullValue(): NovelEntity? =
    kotlinx.coroutines.flow.firstOrNull(this)