package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.NovelDAO
import me.rerere.rikkahub.data.db.entity.NovelChapterEntity
import me.rerere.rikkahub.data.db.entity.NovelEntity
import me.rerere.rikkahub.data.files.FilesManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 章节切分规则：只认行首的章节标题，避免把正文里的「第一章」引用当成新章。 */
private val CHAPTER_HEADING = Regex(
    """^\s*(第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回]|【?\s*第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回]|Chapter\s+\d+)\b?.*$""",
    RegexOption.IGNORE_CASE
)
private val VOLUME_HEADING = Regex(
    """^\s*第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*卷.*$"""
)

class NovelRepository(
    private val dao: NovelDAO,
    private val novelsDir: File,
) {
    fun listNovels(): Flow<List<NovelEntity>> = dao.listNovels()

    fun getNovel(id: String): Flow<NovelEntity?> = dao.getNovel(id)

    fun listChapters(novelId: String): Flow<List<NovelChapterEntity>> = dao.listChapters(novelId)

    /**
     * 导入纯文本小说。
     *
     * 原文按规范化换行后写入应用私有目录，数据库只留路径与摘要；
     * 同一内容重复导入会直接复用已有记录，不会出现两本一样的书。
     */
    suspend fun import(
        raw: String,
        title: String,
        author: String = "",
    ): NovelEntity = withContext(Dispatchers.IO) {
        val text = normalize(raw)
        require(text.isNotBlank()) { "内容为空" }
        val sha = sha256(text)
        dao.findByHash(sha)?.let { return@withContext it }

        val id = UUID.randomUUID().toString()
        novelsDir.mkdirs()
        val target = File(novelsDir, "$id.txt")
        target.writeText(text, Charsets.UTF_8)

        val chapters = splitChapters(text).mapIndexed { index, chunk ->
            NovelChapterEntity(
                id = UUID.randomUUID().toString(),
                novelId = id,
                ordinal = index,
                title = chunk.title,
                start = chunk.start,
                end = chunk.end,
                volume = chunk.volume,
            )
        }
        require(chapters.isNotEmpty()) { "没有识别到任何章节" }

        val novel = NovelEntity(
            id = id,
            title = title.trim().ifBlank { "未命名" },
            author = author.trim(),
            textPath = target.absolutePath,
            sha256 = sha,
            charCount = text.length,
            chapterCount = chapters.size,
            progressChapter = 0,
            progressOffset = 0f,
            lastReadAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
        )
        dao.upsertNovel(novel)
        dao.insertChapters(chapters)
        novel
    }

    /** 读取指定章节正文。每次按需切片，避免把整本书读进内存。 */
    suspend fun readChapter(novel: NovelEntity, ordinal: Int): String = withContext(Dispatchers.IO) {
        val chapter = dao.getChapter(novel.id, ordinal) ?: return@withContext ""
        val text = loadText(novel) ?: return@withContext ""
        if (chapter.start > text.length) return@withContext ""
        text.substring(chapter.start, chapter.end.coerceAtMost(text.length))
    }

    @Volatile
    private var cachedText: String? = null

    @Volatile
    private var cachedNovelId: String? = null

    /** 同一本书连续翻章时复用内存正文，避免每翻一页都重读几十 MB 文件。 */
    private fun loadText(novel: NovelEntity): String? {
        if (cachedNovelId == novel.id) return cachedText
        val file = File(novel.textPath)
        if (!file.exists()) return null
        return file.readText(Charsets.UTF_8).also {
            cachedText = it
            cachedNovelId = novel.id
        }
    }

    suspend fun getChapter(novelId: String, ordinal: Int): NovelChapterEntity? =
        dao.getChapter(novelId, ordinal)

    suspend fun saveProgress(novelId: String, chapter: Int, offset: Float) = withContext(Dispatchers.IO) {
        dao.saveProgress(novelId, chapter, offset.coerceIn(0f, 1f), System.currentTimeMillis())
    }

    suspend fun delete(novel: NovelEntity) = withContext(Dispatchers.IO) {
        runCatching { File(novel.textPath).delete() }
        dao.deleteChapters(novel.id)
        dao.deleteNovel(novel.id)
    }

    private fun normalize(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class Chunk(val title: String, val start: Int, val end: Int, val volume: String)

    /**
     * 切分章节。标题行之前的序章内容会并入第一章，不丢字；
     * 卷名记录到后续章节上，供目录按卷分组。
     */
    private fun splitChapters(text: String): List<Chunk> {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size)
        var cursor = 0
        lines.forEachIndexed { i, line ->
            offsets[i] = cursor
            cursor += line.length + 1
        }

        val heads = ArrayList<Triple<Int, Int, String>>() // lineIndex, charOffset, title
        var volume = ""
        lines.forEachIndexed { i, line ->
            when {
                VOLUME_HEADING.matches(line.trim()) && line.trim().length <= 40 -> volume = line.trim()
                CHAPTER_HEADING.matches(line.trim()) && line.trim().length <= 120 ->
                    heads += Triple(i, offsets[i], line.trim())
            }
        }

        if (heads.isEmpty()) {
            // 无章节标记的短文本当作单章；长文本仍按单章处理，至少能读。
            return listOf(Chunk("正文", 0, text.length, ""))
        }

        val result = ArrayList<Chunk>(heads.size)
        heads.forEachIndexed { index, (lineIndex, charOffset, title) ->
            val bodyStart = (offsets.getOrNull(lineIndex + 1) ?: text.length)
            val end = if (index + 1 < heads.size) heads[index + 1].second else text.length
            result += Chunk(title, bodyStart.coerceAtMost(text.length), end, volume)
        }
        return result
    }
}