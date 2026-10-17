package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一本本地小说及其阅读进度。
 *
 * 原文不重复存进数据库：导入时把规范化后的 UTF-8 文本写入工作区文件，
 * 这里只保存路径、内容摘要和章节切分结果，避免把几十 MB 文本塞进 Room。
 */
@Entity(
    tableName = "novel",
    indices = [Index(value = ["sha256"], unique = true)]
)
data class NovelEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("author")
    val author: String = "",
    /** 工作区 FILES 区内保存的正文文件相对路径。 */
    @ColumnInfo("text_path")
    val textPath: String,
    /** 正文规范化后的 SHA-256，用于检测文件被外部改动。 */
    @ColumnInfo("sha256")
    val sha256: String,
    @ColumnInfo("char_count")
    val charCount: Int,
    @ColumnInfo("chapter_count")
    val chapterCount: Int,
    /** 当前读到第几章（0 起）。 */
    @ColumnInfo("progress_chapter", defaultValue = "0")
    val progressChapter: Int = 0,
    /** 当前章内滚动比例，0f..1f。 */
    @ColumnInfo("progress_offset", defaultValue = "0")
    val progressOffset: Float = 0f,
    @ColumnInfo("last_read_at", defaultValue = "0")
    val lastReadAt: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/**
 * 章节切分结果。原文行号与字符偏移同时保存：
 * 行号便于人查看定位，字符偏移用于直接切开正文而不用重新扫描。
 */
@Entity(
    tableName = "novel_chapter",
    indices = [Index(value = ["novel_id", "ordinal"], unique = true)]
)
data class NovelChapterEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("novel_id")
    val novelId: String,
    /** 书内顺序，0 起。 */
    @ColumnInfo("ordinal")
    val ordinal: Int,
    /** 原始标题行，例如「第二章 楔子 一块黑布」。 */
    @ColumnInfo("title")
    val title: String,
    /** 标题行之后的正文起点（字符偏移）。 */
    @ColumnInfo("start")
    val start: Int,
    @ColumnInfo("end")
    val end: Int,
    @ColumnInfo("volume")
    val volume: String = "",
)