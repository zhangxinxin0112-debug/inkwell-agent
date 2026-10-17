package me.rerere.rikkahub.ui.pages.novel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.NovelEntity
import me.rerere.rikkahub.data.repository.NovelRepository
import java.io.File

class NovelShelfVM(
    private val repository: NovelRepository,
    private val context: Context,
) : ViewModel() {

    val novels: StateFlow<List<NovelEntity>> = repository.listNovels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun importFile(uri: Uri): Result<NovelEntity> = runCatching {
        val name = queryDisplayName(uri) ?: "未命名"
        val raw = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
        }
        val text = decode(raw)
        val title = name.substringBeforeLast('.').ifBlank { "未命名" }
        repository.import(text, title)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex("_display_name")
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.let { File(it).name }

    /** TXT 常见编码：优先 UTF-8，其次 GB18030（国内 txt 常用）。 */
    private fun decode(bytes: ByteArray): String {
        val head = bytes.take(3).toByteArray()
        if (head.size == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return runCatching { String(bytes, Charsets.UTF_8) }
            .getOrElse { String(bytes, charset("GB18030")) }
    }
}