package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WritingCommandTest {
    @Test
    fun shellArgumentsRemainLiteral() {
        val payload = """{"title":"a' ${'$'}(touch /tmp/should-not-exist) `id`"}"""
        val command = writingCommand("novels/my book", "init", payload, "/builtin_skills/fiction-writing/scripts/writing.py")
        assertEquals(
            "python3 '/builtin_skills/fiction-writing/scripts/writing.py' --project '/workspace/novels/my book' --action 'init' --json '" +
                payload.replace("'", "'\"'\"'") + "'",
            command,
        )
    }

    @Test
    fun rejectsTraversalAndUnknownActions() {
        listOf("../other", "/root", "a/../b", "a//b", "a\nb").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                writingCommand(path, "init", "{}", "script.py")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            writingCommand("novel", "delete_everything", "{}", "script.py")
        }
    }
}