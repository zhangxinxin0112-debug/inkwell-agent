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

@Serializable
data class NovelChapter(
    val ordinal: Int,
    val title: String,
    val start: Int,
    val end: Int,
    val volume: String = "",
)

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
    val chapters: List<NovelChapter> = emptyList(),
)

/**
 * 小说书架与阅读进度。
 *
 * 刻意不放进 Room：阅读功能是二改追加的，改动上游数据库版本会在升级时触发迁移失败，
 * 也会让后续跟随 RikkaHub 更新更难合并。这里用独立的 JSON 索引 + 纯文本正文，
 * 目录位于应用私有 filesDir/novels/，与聊天数据互不影响。
 */
class NovelStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val root: File by lazy { File(context.filesDir, "novels").apply { mkdirs() } }
    private val indexFile: File by lazy { File(root, "index.json") }

    private val _novels = MutableStateFlow<List<NovelMeta>>(emptyList())
    val novels: StateFlow<List<NovelMeta>> = _novels.asStateFlow()

    @Volatile
    private var loaded = false

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

    private fun persist(list: List<NovelMeta>) {
        val temp = File(root, "index.json.tmp")
        temp.writeText(json.encodeToString(list))
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(json.encodeToString(list))
            temp.delete()
        }
        _novels.value = list.sortedByDescending { it.lastReadAt }
    }

    suspend fun import(raw: String, title: String, author: String = ""): NovelMeta =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            require(text.isNotBlank()) { "内容为空" }
            val sha = sha256(text)
            _novels.value.firstOrNull { it.sha256 == sha }?.let { return@withContext it }

            val id = UUID.randomUUID().toString()
            val chapters = splitChapters(text)
            File(root, "$id.txt").writeText(text, Charsets.UTF_8)
            val meta = NovelMeta(
                id = id,
                title = title.trim().ifBlank { "未命名" },
                author = author.trim(),
                sha256 = sha,
                charCount = text.length,
                chapterCount = chapters.size,
                createdAt = System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis(),
                chapters = chapters,
            )
            persist(_novels.value + meta)
            meta
        }

    suspend fun readChapter(id: String, ordinal: Int): Pair<String, String> =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            val meta = _novels.value.firstOrNull { it.id == id } ?: return@withContext "" to ""
            val chapter = meta.chapters.getOrNull(ordinal) ?: return@withContext "" to ""
            val file = File(root, "$id.txt")
            if (!file.exists()) return@withContext chapter.title to ""
            val cached = cacheFor(id, file)
            val end = chapter.end.coerceAtMost(cached.length)
            val start = chapter.start.coerceAtMost(end)
            chapter.title to cached.substring(start, end)
        }

    private var cachedId: String? = null
    private var cachedText: String? = null

    @Synchronized
    private fun cacheFor(id: String, file: File): String {
        if (cachedId == id) cachedText?.let { return it }
        val text = file.readText(Charsets.UTF_8)
        cachedId = id
        cachedText = text
        return text
    }

    suspend fun saveProgress(id: String, chapter: Int, offset: Float) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val updated = _novels.value.map {
            if (it.id == id) it.copy(
                progressChapter = chapter,
                progressOffset = offset.coerceIn(0f, 1f),
                lastReadAt = System.currentTimeMillis(),
            ) else it
        }
        persist(updated)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ensureLoaded()
        File(root, "$id.txt").delete()
        if (cachedId == id) {
            cachedId = null
            cachedText = null
        }
        persist(_novels.value.filterNot { it.id == id })
    }

    fun find(id: String): NovelMeta? {
        ensureLoaded()
        return _novels.value.firstOrNull { it.id == id }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val chapterHeading = Regex(
        """^\s*第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回].*$"""
    )
    private val volumeHeading = Regex("""^\s*第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*卷.*$""")

    private fun splitChapters(text: String): List<NovelChapter> {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size)
        var cursor = 0
        lines.forEachIndexed { i, line ->
            offsets[i] = cursor
            cursor += line.length + 1
        }
        val heads = ArrayList<Pair<Int, String>>()
        var volume = ""
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.length <= 40 && volumeHeading.matches(line) -> volume = line
                line.length <= 120 && chapterHeading.matches(line) -> heads += i to line
            }
        }
        if (heads.isEmpty()) {
            return listOf(NovelChapter(0, "正文", 0, text.length, ""))
        }
        return heads.mapIndexed { index, (lineIndex, title) ->
            val start = (offsets.getOrNull(lineIndex + 1) ?: text.length)
            val end = if (index + 1 < heads.size) offsets[heads[index + 1].first] else text.length
            NovelChapter(index, title, start, end.coerceAtMost(text.length), volume)
        }
    }
}