package me.rerere.rikkahub.ui.pages.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ListView
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.rikkahub.data.novel.NovelChapter
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderPage(
    novelId: String,
    vm: NovelReaderVM = koinViewModel(parameters = { parametersOf(novelId) }),
) {
    val navController = LocalNavController.current
    val novel by vm.novel.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val typeVersion by vm.typeVersion.collectAsStateWithLifecycle()

    var showCatalog by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // 排版参数变化后重新读取，触发正文重组。
    val fontSize = remember(typeVersion) { vm.fontSize }
    val lineHeight = remember(typeVersion) { vm.lineHeight }

    // 首次进入时恢复上次的章内位置。
    LaunchedEffect(state.chapter, state.loading) {
        if (!state.loading && state.offset > 0f && scroll.value == 0) {
            scroll.scrollTo((scroll.maxValue * state.offset).toInt())
        }
    }
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
                                text = "${state.chapter + 1} / ${novel!!.chapterCount}",
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
                    IconButton(onClick = { showType = true }) {
                        Icon(HugeIcons.TextFont, contentDescription = "排版")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (state.missing) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("这本书已不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 22.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 20.dp),
                    )
                    Text(
                        text = state.body
                            .split('\n')
                            .filter { it.isNotBlank() }
                            .joinToString("\n\n") { it.trim() },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * lineHeight).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = {
                                vm.prev()
                                scope.launch { scroll.scrollTo(0) }
                            },
                            enabled = state.chapter > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("上一章") }
                        TextButton(
                            onClick = {
                                vm.next()
                                scope.launch { scroll.scrollTo(0) }
                            },
                            enabled = novel != null && state.chapter + 1 < (novel?.chapterCount ?: 0),
                            modifier = Modifier.weight(1f),
                        ) { Text("下一章") }
                    }
                }
            }
        }
    }

    if (showCatalog) {
        ModalBottomSheet(
            onDismissRequest = { showCatalog = false },
            sheetState = sheetState,
        ) {
            CatalogSheet(
                chapters = novel?.chapters.orEmpty(),
                current = state.chapter,
                onSelect = {
                    vm.open(it)
                    showCatalog = false
                    scope.launch { scroll.scrollTo(0) }
                },
            )
        }
    }
    if (showType) {
        ModalBottomSheet(
            onDismissRequest = { showType = false },
            sheetState = sheetState,
        ) {
            TypeSheet(
                fontSize = fontSize,
                lineHeight = lineHeight,
                onFontSize = vm::changeFontSize,
                onLineHeight = vm::changeLineHeight,
            )
        }
    }
}

@Composable
private fun CatalogSheet(
    chapters: List<NovelChapter>,
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
            items(chapters, key = { it.ordinal }) { chapter ->
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
private fun TypeSheet(
    fontSize: Int,
    lineHeight: Float,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
) {
    var size by remember { mutableIntStateOf(fontSize) }
    var spacing by remember { mutableFloatStateOf(lineHeight) }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("排版", style = MaterialTheme.typography.titleMedium)
        Text("字号 $size", style = MaterialTheme.typography.labelMedium)
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