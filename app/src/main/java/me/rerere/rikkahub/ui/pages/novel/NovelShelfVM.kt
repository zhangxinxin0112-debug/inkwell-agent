package me.rerere.rikkahub.ui.pages.novel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.novel.NovelMeta
import me.rerere.rikkahub.data.novel.NovelStore
import java.io.File

class NovelShelfVM(
    private val store: NovelStore,
    private val context: Context,
) : ViewModel() {

    init {
        store.ensureLoaded()
    }

    val novels: StateFlow<List<NovelMeta>> = store.novels

    suspend fun import(uri: Uri): Result<NovelMeta> = runCatching {
        val name = displayName(uri) ?: "未命名.txt"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取文件")
        }
        val text = decode(bytes)
        store.import(text, name.substringBeforeLast('.').ifBlank { "未命名" })
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex("_display_name")
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.let { File(it).name }

    /** TXT 常见编码：带 BOM 的 UTF-8、普通 UTF-8，最后退到 GB18030。 */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // 含替换字符说明不是合法 UTF-8，改按国标解码。
        return if ('\uFFFD' in utf8) String(bytes, charset("GB18030")) else utf8
    }
}