package com.autodrops.agent

/** Pure helpers for the on-phone coding/dev environment (file tree rendering, run formatting).
 *  Kept free of Android deps so it is fully unit-testable. */
object DevTree {
    data class Row(val name: String, val path: String, val depth: Int, val isDir: Boolean)

    /** Build an indented tree (directories + files) from a list of workspace-relative file paths. */
    fun build(paths: List<String>): List<Row> {
        val dirs = sortedSetOf<String>()
        for (p in paths) {
            val parts = p.split('/')
            var acc = ""
            for (i in 0 until parts.size - 1) {
                acc = if (acc.isEmpty()) parts[i] else "$acc/${parts[i]}"
                dirs.add(acc)
            }
        }
        val entries = (dirs.map { it to true } + paths.map { it to false }).sortedBy { it.first }
        return entries.map { (path, isDir) ->
            Row(path.substringAfterLast('/'), path, path.count { it == '/' }, isDir)
        }
    }

    /** Render the tree as a plain-text listing (used by the file_list tool result in chat). */
    fun render(paths: List<String>): String {
        if (paths.isEmpty()) return "(empty)"
        return build(paths).joinToString("\n") { r ->
            "  ".repeat(r.depth) + (if (r.isDir) "\uD83D\uDCC1 " else "\u2022 ") + r.name
        }
    }

    /** Format a command + its combined stdout/stderr for a run_script tool result shown in chat. */
    fun formatRun(cmd: String, output: String): String = "$ $cmd\n$output"
}
