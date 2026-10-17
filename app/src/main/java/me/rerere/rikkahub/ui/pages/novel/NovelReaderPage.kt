package me.rerere.rikkahub.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ListView
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

/**
 * 阅读器。沿用聊天页的 Scaffold / TopAppBar / 圆角卡片与配色，
 * 不做另一套视觉；正文用可调字号与行距的纯文本排版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderPage(
    novelId: String,
    vm: NovelReaderVM = koinViewModel(parameters = { org.koin.core.parameter.parametersOf(novelId) }),
) {
    val navController = LocalNavController.current
    val novel by vm.novel.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val body by vm.body.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()

    var showCatalog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // 恢复上次阅读位置
    LaunchedEffect(novel?.id, reading.chapter) {
        if (reading.offset > 0f && scroll.value == 0) {
            scroll.scrollTo((scroll.maxValue * reading.offset).toInt())
        }
    }
    // 离开页面时保存滚动位置
    LaunchedEffect(scroll.value) {
        if (scroll.maxValue > 0) {
            vm.saveOffset(scroll.value.toFloat() / scroll.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = novel?.title ?: "阅读",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        if (novel != null) {
                            Text(
                                text = "${reading.chapter + 1} / ${novel!!.chapterCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCatalog = true }) {
                        Icon(HugeIcons.ListView, contentDescription = "目录")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(HugeIcons.TextFont, contentDescription = "排版")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        when {
            novel == null || (body == null && !reading.loading) -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("找不到这本书", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                if (reading.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 22.dp),
                ) {
                    Text(
                        text = reading.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 20.dp),
                    )
                    Text(
                        text = buildAnnotatedString {
                            body.orEmpty().trim().split('\n').forEach { line ->
                                if (line.isNotBlank()) {
                                    withStyle(SpanStyle(fontSize = vm.fontSize.sp)) {
                                        append(line.trim())
                                    }
                                    append("\n\n")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = (vm.fontSize * vm.lineHeight).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = { vm.prevChapter(); scope.launch { scroll.scrollTo(0) } },
                            enabled = reading.chapter > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("上一章") }
                        TextButton(
                            onClick = { vm.nextChapter(); scope.launch { scroll.scrollTo(0) } },
                            enabled = novel != null && reading.chapter + 1 < (novel?.chapterCount ?: 0),
                            modifier = Modifier.weight(1f),
                        ) { Text("下一章") }
                    }
                }
            }
        }
    }

    if (showCatalog) {
        ModalBottomSheet(onDismissRequest = { showCatalog = false }, sheetState = sheetState) {
            NovelCatalog(
                chapters = chapters,
                current = reading.chapter,
                onSelect = {
                    vm.openChapter(it)
                    showCatalog = false
                    scope.launch { scroll.scrollTo(0) }
                },
            )
        }
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = sheetState) {
            TypeSettings(
                fontSize = vm.fontSize,
                lineHeight = vm.lineHeight,
                onFontSize = vm::setFontSize,
                onLineHeight = vm::setLineHeight,
            )
        }
    }
}

@Composable
private fun NovelCatalog(
    chapters: List<me.rerere.rikkahub.data.db.entity.NovelChapterEntity>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current in chapters.indices) listState.scrollToItem(current)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            text = "目录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(chapters, key = { it.id }) { chapter ->
                val selected = chapter.ordinal == current
                Surface(
                    onClick = { onSelect(chapter.ordinal) },
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeSettings(
    fontSize: Int,
    lineHeight: Float,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
) {
    var size by remember { mutableIntStateOf(fontSize) }
    var spacing by remember { mutableStateOf(lineHeight) }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("排版", style = MaterialTheme.typography.titleMedium)
        Text("字号 ${size}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = size.toFloat(),
            onValueChange = { size = it.toInt(); onFontSize(it.toInt()) },
            valueRange = 14f..26f,
            steps = 11,
        )
        Text("行距 ${"%.1f".format(spacing)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = spacing,
            onValueChange = { spacing = it; onLineHeight(it) },
            valueRange = 1.3f..2.4f,
            steps = 10,
        )
    }
}