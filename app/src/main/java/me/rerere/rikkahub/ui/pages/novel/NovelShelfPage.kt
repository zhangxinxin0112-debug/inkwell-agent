package me.rerere.rikkahub.ui.pages.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.novel.NovelMeta
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/** 书架：展示已导入小说与阅读进度，沿用聊天页的顶栏与卡片样式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelShelfPage(vm: NovelShelfVM = koinViewModel()) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val novels by vm.novels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<NovelMeta?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                vm.import(uri)
                    .onSuccess { toaster.show("已导入《${it.title}》") }
                    .onFailure { toaster.show("导入失败：${it.message}") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("text/plain", "text/*")) }) {
                        Icon(HugeIcons.Add01, contentDescription = "导入小说")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (novels.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点右上角导入 TXT 小说",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(novels, key = { it.id }) { novel ->
                    NovelRow(
                        novel = novel,
                        onClick = { navController.navigate(Screen.NovelReader(novel.id)) },
                        onLongClick = { pendingDelete = novel },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这本书？") },
            text = { Text("《${target.title}》及其阅读进度会一并删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target.id)
                        pendingDelete = null
                        toaster.show("已删除《${target.title}》")
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelRow(novel: NovelMeta, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = novel.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildString {
                        if (novel.author.isNotBlank()) append(novel.author).append(" · ")
                        append("${novel.chapterCount} 章")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "读到 ${novel.progressChapter + 1} 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (novel.chapterCount == 0) 0f
                    else (novel.progressChapter + novel.progressOffset) / novel.chapterCount
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}