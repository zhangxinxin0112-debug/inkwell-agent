package me.rerere.rikkahub.ui.pages.novel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/** 阅读排版偏好，进程内保留；不写入全局设置，避免污染上游设置结构。 */
class NovelTypePreferences {
    var fontSize by mutableIntStateOf(18)
    var lineHeight by mutableFloatStateOf(1.8f)
}