package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.NovelChapterEntity
import me.rerere.rikkahub.data.db.entity.NovelEntity

@Dao
interface NovelDAO {
    @Query("SELECT * FROM novel ORDER BY last_read_at DESC, created_at DESC")
    fun listNovels(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novel WHERE id = :id")
    fun getNovel(id: String): Flow<NovelEntity?>

    @Query("SELECT * FROM novel WHERE id = :id")
    suspend fun findNovel(id: String): NovelEntity?

    @Query("SELECT * FROM novel WHERE sha256 = :sha")
    suspend fun findByHash(sha: String): NovelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNovel(novel: NovelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<NovelChapterEntity>)

    @Query("SELECT * FROM novel_chapter WHERE novel_id = :novelId ORDER BY ordinal ASC")
    fun listChapters(novelId: String): Flow<List<NovelChapterEntity>>

    @Query("SELECT * FROM novel_chapter WHERE novel_id = :novelId ORDER BY ordinal ASC")
    suspend fun getChapters(novelId: String): List<NovelChapterEntity>

    @Query("SELECT * FROM novel_chapter WHERE novel_id = :novelId AND ordinal = :ordinal")
    suspend fun getChapter(novelId: String, ordinal: Int): NovelChapterEntity?

    @Query(
        "UPDATE novel SET progress_chapter = :chapter, progress_offset = :offset, " +
            "last_read_at = :readAt WHERE id = :id"
    )
    suspend fun saveProgress(id: String, chapter: Int, offset: Float, readAt: Long)

    @Query("DELETE FROM novel WHERE id = :id")
    suspend fun deleteNovel(id: String)

    @Query("DELETE FROM novel_chapter WHERE novel_id = :id")
    suspend fun deleteChapters(id: String)
}