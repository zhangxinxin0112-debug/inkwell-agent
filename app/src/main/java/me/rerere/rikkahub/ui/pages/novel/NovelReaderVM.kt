package me.rerere.rikkahub.ui.pages.novel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.novel.ChapterEntry
import me.rerere.rikkahub.data.novel.NovelMeta
import me.rerere.rikkahub.data.novel.NovelStore

/** 单页最多渲染的字数；超长章按页切开，避免 Compose 一次性排版几十万字。 */
private const val PAGE_CHARS = 6000

data class ReaderState(
    val chapter: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1,
    val title: String = "",
    val body: String = "",
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

    private val _chapters = MutableStateFlow<List<ChapterEntry>>(emptyList())
    val chapters: StateFlow<List<ChapterEntry>> = _chapters.asStateFlow()

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
            _chapters.value = store.chapters(novelId)
            loadPage(meta.progressChapter, page = 0)
        }
    }

    private suspend fun loadPage(ordinal: Int, page: Int) {
        _state.value = _state.value.copy(chapter = ordinal, loading = true)
        val total = store.chapterLength(novelId, ordinal)
        val pageCount = if (total <= 0) 1 else ((total + PAGE_CHARS - 1) / PAGE_CHARS)
        val safePage = page.coerceIn(0, pageCount - 1)
        val (title, body) = store.readChapter(
            id = novelId,
            ordinal = ordinal,
            fromChapterStart = safePage * PAGE_CHARS,
            limit = PAGE_CHARS,
        )
        _state.value = ReaderState(
            chapter = ordinal,
            page = safePage,
            pageCount = pageCount,
            title = title,
            body = body,
            loading = false,
        )
        store.saveProgress(novelId, ordinal, safePage.toFloat() / pageCount)
    }

    /** 下一页；已到章末则进入下一章。 */
    fun nextPage() {
        val s = _state.value
        viewModelScope.launch {
            if (s.page + 1 < s.pageCount) loadPage(s.chapter, s.page + 1)
            else {
                val total = _novel.value?.chapterCount ?: 0
                if (s.chapter + 1 < total) loadPage(s.chapter + 1, 0)
            }
        }
    }

    /** 上一页；已在章首则回到上一章最后一页。 */
    fun prevPage() {
        val s = _state.value
        viewModelScope.launch {
            if (s.page > 0) loadPage(s.chapter, s.page - 1)
            else if (s.chapter > 0) {
                val prev = s.chapter - 1
                val total = store.chapterLength(novelId, prev)
                val lastPage = if (total <= 0) 0 else (total + PAGE_CHARS - 1) / PAGE_CHARS - 1
                loadPage(prev, lastPage)
            }
        }
    }

    fun open(ordinal: Int) {
        viewModelScope.launch { loadPage(ordinal, 0) }
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