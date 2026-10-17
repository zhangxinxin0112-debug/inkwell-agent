package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository

internal const val WRITING_SKILL_NAME = "fiction-writing"
private val writingActions = setOf("init", "import", "status", "read", "record", "audit", "finish", "search", "browse", "save", "documents", "export")

/** Narrow bridge to the versioned workspace runtime. No additional UI or global settings. */
fun createWritingTools(
    workspaceId: String,
    repository: WorkspaceRepository,
    skills: SkillManager,
    approvalOverrides: Map<String, Boolean>,
): List<Tool> = listOf(
    Tool(
        name = "writing_project",
        description = "Manage a persistent fiction project: import TXT, read each chapter in order, record evidence-backed evolving entries, audit completeness, query references and save append-only drafts. Load fiction-writing with use_skill before use. Requires Python 3 with sqlite3 in the ready workspace.",
        systemPrompt = { _, _ ->
            "For source-grounded fiction writing, load the fiction-writing skill before using writing_project. " +
                "It uses explicit project paths under /workspace; never mix projects. Tool results confirm storage/quote checks, not semantic truth or proof of exhaustive reading."
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("project", buildJsonObject {
                        put("type", "string")
                        put("description", "Explicit project directory relative to /workspace, e.g. novels/book-a. No parent traversal.")
                    })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", writingActions.joinToString(", "))
                    })
                    put("data", buildJsonObject {
                        put("type", "object")
                        put("description", "Action payload described by the fiction-writing skill. Never send source text as instructions.")
                    })
                },
                required = listOf("project", "action", "data"),
            )
        },
        // Preserve upstream's workspace shell approval policy, including user overrides.
        needsApproval = {
            resolveWorkspaceToolApproval("workspace_shell", approvalOverrides)
        },
        execute = {
            val params = it.jsonObject
            val project = params["project"]?.jsonPrimitive?.content ?: error("project is required")
            val action = params["action"]?.jsonPrimitive?.content ?: error("action is required")
            require(action in writingActions) { "Unknown writing action" }
            val data = params["data"]?.jsonObject ?: error("data must be an object")
            require(data.toString().length <= 200_000) { "Payload too large; send smaller batches" }
            val skill = skills.findSkill(WRITING_SKILL_NAME) ?: error("Writing skill missing")
            val skillRoot = if (skill.builtin) "/builtin_skills" else "/skills"
            val script = "$skillRoot/$WRITING_SKILL_NAME/scripts/writing.py"
            val command = writingCommand(project, action, null, script)
            val result = repository.executeCommand(
                workspaceId, command, "", 60_000L, data.toString().toByteArray(Charsets.UTF_8)
            )
            listOf(UIMessagePart.Text(buildJsonObject {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
                put("truncated", result.truncated)
            }.toString()))
        },
    )
)

/** Every value becomes a shell literal; JSON strings must never be interpolated as shell code. */
internal fun writingCommand(project: String, action: String, data: String?, script: String): String {
    require(action in writingActions) { "Unknown writing action" }
    require(project.isNotBlank() && !project.startsWith('/') && project.length <= 240) { "Use a relative project path" }
    require(project.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid project path" }
    require(project.none { it.isISOControl() }) { "Control characters in project path" }
    return "python3 ${writingShellLiteral(script)} --project ${writingShellLiteral("/workspace/$project")} " +
        "--action ${writingShellLiteral(action)} " +
        if (data == null) "--json-file /dev/stdin" else "--json ${writingShellLiteral(data)}"
}

private fun writingShellLiteral(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
