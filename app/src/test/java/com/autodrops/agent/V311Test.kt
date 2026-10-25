package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v3.11: Codex-like on-phone coding/dev environment — file tree, editor save, run-script streaming, tool wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V311Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun store(): SecureStore {
        val p = ctx.getSharedPreferences("v311_settings", Context.MODE_PRIVATE)
        p.edit().clear().commit()
        return SecureStore(p)
    }
    private fun reg() = ToolRegistry(ctx, store())

    // ---- File tree building ----
    @Test fun tree_builds_dirs_and_files_with_depth() {
        val rows = DevTree.build(listOf("a.txt", "src/main.py", "src/lib/util.py"))
        val byPath = rows.associateBy { it.path }
        assertTrue(byPath.containsKey("src"))
        assertTrue(byPath.getValue("src").isDir)
        assertEquals(0, byPath.getValue("src").depth)
        assertEquals("util.py", byPath.getValue("src/lib/util.py").name)
        assertEquals(2, byPath.getValue("src/lib/util.py").depth)
        assertFalse(byPath.getValue("src/lib/util.py").isDir)
        // a directory appears before its children
        assertTrue(rows.indexOfFirst { it.path == "src" } < rows.indexOfFirst { it.path == "src/main.py" })
    }

    @Test fun tree_render_empty_and_nonempty() {
        assertEquals("(empty)", DevTree.render(emptyList()))
        val r = DevTree.render(listOf("src/x.kt"))
        assertTrue(r.contains("src"))
        assertTrue(r.contains("x.kt"))
    }

    @Test fun formatRun_prefixes_command() {
        assertEquals("$ ls\nexit=0\nfile", DevTree.formatRun("ls", "exit=0\nfile"))
    }

    // ---- Tool registry wiring ----
    @Test fun new_dev_tools_are_registered() {
        val names = reg().specs.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("file_list", "file_read", "file_write", "run_script")))
        // gate-not-remove: Termux path preserved under a new name, existing file_op/shell kept
        assertTrue(names.contains("termux_run"))
        assertTrue(names.contains("file_op"))
        assertTrue(names.contains("shell"))
    }

    @Test fun risk_policy_is_fail_closed_for_writes_and_scripts() {
        val specs = reg().specs.associateBy { it.name }
        assertEquals(Risk.LOW, specs.getValue("file_list").risk(JSONObject()))
        assertEquals(Risk.LOW, specs.getValue("file_read").risk(JSONObject()))
        assertEquals(Risk.EFFECT, specs.getValue("file_write").risk(JSONObject()))
        // sandbox backend default => HIGH (needs confirmation), never LOW
        assertEquals(Risk.HIGH, specs.getValue("run_script").risk(JSONObject()))
    }

    // ---- File write -> read -> list round-trip through the tools ----
    @Test fun file_write_then_read_then_list_roundtrip() = runBlocking {
        val r = reg()
        val specs = r.specs.associateBy { it.name }
        // clean workspace file
        val w = specs.getValue("file_write").exec(JSONObject().put("path", "proj/hello.txt").put("content", "hi there"))
        assertTrue(w.contains("wrote"))
        val read = specs.getValue("file_read").exec(JSONObject().put("path", "proj/hello.txt"))
        assertEquals("hi there", read)
        val list = specs.getValue("file_list").exec(JSONObject().put("path", ""))
        assertTrue(list.contains("hello.txt"))
        assertTrue(list.contains("proj"))
    }

    @Test fun file_tools_reject_path_traversal() = runBlocking {
        val specs = reg().specs.associateBy { it.name }
        val read = specs.getValue("file_read").exec(JSONObject().put("path", "../../../etc/passwd"))
        assertEquals("path escapes workspace", read)
        val write = specs.getValue("file_write").exec(JSONObject().put("path", "../escape.txt").put("content", "x"))
        assertEquals("path escapes workspace", write)
    }

    @Test fun run_script_streams_command_and_output() = runBlocking {
        val specs = reg().specs.associateBy { it.name }
        val out = specs.getValue("run_script").exec(JSONObject().put("command", "echo dron-ok"))
        assertTrue("must echo the command", out.startsWith("$ echo dron-ok"))
        assertTrue("must include stdout", out.contains("dron-ok"))
        assertTrue("must include exit header", out.contains("exit="))
    }
}
