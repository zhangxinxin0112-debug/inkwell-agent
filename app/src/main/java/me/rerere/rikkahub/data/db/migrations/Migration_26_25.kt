package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 26 → 25 降级迁移。
 *
 * 早期试验版曾把数据库升到 26（加入了 novel / novel_chapter 两张表），
 * 但书架数据后来改为独立的文件存储，正式版本保持在上游的 25。
 * 已经装过 26 的设备向下安装 25 时，Room 默认会拒绝降级并崩溃，
 * 这里显式删掉那两张试验表，让数据库回到 25 的结构。
 */
val Migration_26_25 = object : Migration(26, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `novel_chapter`")
        db.execSQL("DROP TABLE IF EXISTS `novel`")
    }
}