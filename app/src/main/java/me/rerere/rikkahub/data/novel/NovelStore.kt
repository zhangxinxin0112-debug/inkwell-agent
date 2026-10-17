package me.rerere.rikkahub.data.novel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 书架条目：只存元数据。章节表单独放 .toc 文件，避免一个巨大的 JSON。 */
@Serializable
data class NovelMeta(
    val id: String,
    val title: String,
    val author: String = "",
    val sha256: String,
    val charCount: Int,
    val chapterCount: Int,
    val progressChapter: Int = 0,
    val progressOffset: Float = 0f,
    val lastReadAt: Long = 0,
    val createdAt: Long = 0,
)

@Serializable
data class ChapterEntry(
    val ordinal: Int,
    val title: String,
    val start: Int,
    val end: Int,
)

/**
 * 小说书架、目录与阅读进度。
 *
 * 不放进 Room：阅读是二改追加功能，改上游数据库版本会触发迁移失败，
 * 也让跟随 RikkaHub 更新更难合并。这里全部用应用私有目录下的文件：
 *   novels/index.json      书架元数据
 *   novels/<id>.txt        规范化正文
 *   novels/<id>.toc.json   章节切分结果
 */
class NovelStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root: File by lazy { File(context.filesDir, "novels").apply { mkdirs() } }
    private val indexFile: File by lazy { File(root, "index.json") }

    private val _novels = MutableStateFlow<List<NovelMeta>>(emptyList())
    val novels: StateFlow<List<NovelMeta>> = _novels.asStateFlow()

    @Volatile
    private var loaded = false
    private val tocCache = HashMap<String, List<ChapterEntry>>()

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _novels.value = runCatching {
            if (!indexFile.exists()) emptyList()
            else json.decodeFromString<List<NovelMeta>>(indexFile.readText())
                .sortedByDescending { it.lastReadAt }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val list = _novels.value
        val temp = File(root, "index.json.tmp")
        temp.writeText(json.encodeToString(list))
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(json.encodeToString(list))
            temp.delete()
        }
    }

    suspend fun import(raw: String, title: String, author: String = ""): NovelMeta =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            require(text.isNotBlank()) { "内容为空" }
            val sha = sha256(text)
            _novels.value.firstOrNull { it.sha256 == sha }?.let { return@withContext it }

            val id = UUID.randomUUID().toString()
            File(root, "$id.txt").writeText(text, Charsets.UTF_8)
            val toc = splitChapters(text)
            File(root, "$id.toc.json").writeText(json.encodeToString(toc))
            tocCache[id] = toc

            val meta = NovelMeta(
                id = id,
                title = title.trim().ifBlank { "未命名" },
                author = author.trim(),
                sha256 = sha,
                charCount = text.length,
                chapterCount = toc.size,
                createdAt = System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis(),
            )
            _novels.value = _novels.value + meta
            persist()
            meta
        }

    fun chapters(id: String): List<ChapterEntry> {
        tocCache[id]?.let { return it }
        val file = File(root, "$id.toc.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChapterEntry>>(file.readText())
        }.getOrDefault(emptyList()).also { tocCache[id] = it }
    }

    /**
     * 取一章的指定区间。长章由调用方分页，避免把几十万字一次交给 Compose。
     */
    suspend fun readChapter(
        id: String,
        ordinal: Int,
        fromChapterStart: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        ensureLoaded()
        val chapter = chapters(id).getOrNull(ordinal) ?: return@withContext "" to ""
        val file = File(root, "$id.txt")
        if (!file.exists()) return@withContext chapter.title to ""
        val text = loadText(id, file)
        val chapterEnd = chapter.end.coerceAtMost(text.length)
        val start = (chapter.start + fromChapterStart).coerceIn(chapter.start, chapterEnd)
        val end = if (limit >= chapterEnd - start) chapterEnd else start + limit
        chapter.title to text.substring(start, end)
    }

    fun chapterLength(id: String, ordinal: Int): Int {
        val chapter = chapters(id).getOrNull(ordinal) ?: return 0
        return (chapter.end - chapter.start).coerceAtLeast(0)
    }

    private var cachedId: String? = null
    private var cachedText: String? = null

    @Synchronized
    private fun loadText(id: String, file: File): String {
        if (cachedId == id) cachedText?.let { return it }
        val text = file.readText(Charsets.UTF_8)
        cachedId = id
        cachedText = text
        return text
    }

    suspend fun saveProgress(id: String, chapter: Int, offset: Float) = withContext(Dispatchers.IO) {
        ensureLoaded()
        _novels.value = _novels.value.map {
            if (it.id == id) it.copy(
                progressChapter = chapter,
                progressOffset = offset.coerceIn(0f, 1f),
                lastReadAt = System.currentTimeMillis(),
            ) else it
        }
        persist()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ensureLoaded()
        File(root, "$id.txt").delete()
        File(root, "$id.toc.json").delete()
        if (cachedId == id) {
            cachedId = null
            cachedText = null
        }
        tocCache.remove(id)
        _novels.value = _novels.value.filterNot { it.id == id }
        persist()
    }

    fun find(id: String): NovelMeta? {
        ensureLoaded()
        return _novels.value.firstOrNull { it.id == id }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val chapterRegex = Regex("""^第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回].*""")
    private val volumeRegex = Regex("""^第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*卷.*""")

    private fun splitChapters(text: String): List<ChapterEntry> {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size)
        var cursor = 0
        for (i in lines.indices) {
            offsets[i] = cursor
            cursor += lines[i].length + 1
        }
        val heads = ArrayList<Pair<Int, String>>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.length > 120) continue
            if (volumeRegex.matches(line)) continue
            if (chapterRegex.matches(line)) heads += i to line
        }
        if (heads.isEmpty()) {
            return listOf(ChapterEntry(0, "正文", 0, text.length))
        }
        return heads.mapIndexed { index, (lineIndex, title) ->
            val start = if (lineIndex + 1 < offsets.size) offsets[lineIndex + 1] else text.length
            val end = if (index + 1 < heads.size) offsets[heads[index + 1].first] else text.length
            ChapterEntry(index, title, start.coerceAtMost(text.length), end.coerceAtMost(text.length))
        }
    }
}