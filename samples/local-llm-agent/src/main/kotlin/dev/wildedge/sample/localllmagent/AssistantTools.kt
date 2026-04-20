package dev.wildedge.sample.localllmagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dev.wildedge.sdk.SpanContext
import dev.wildedge.sdk.SpanKind
import dev.wildedge.sdk.WildEdgeClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantTools(
    private val context: Context,
    private val wildEdge: WildEdgeClient,
    private val onToolCall: (name: String) -> Unit,
) : ToolSet {

    var currentTurnSpan: SpanContext? = null

    private val prefs = context.getSharedPreferences("agent_memory", Context.MODE_PRIVATE)
    private val notesDir = File(context.filesDir, "notes").also { it.mkdirs() }

    // -- Time --

    @Tool(description = "Returns the current date and time.")
    fun getTime(): String = wildEdge.trace("get_time", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("get_time")
        SimpleDateFormat("EEEE, MMMM d yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
    }

    // -- Device --

    @Tool(description = "Returns device information: model name, Android version, battery percentage, and free storage in MB.")
    fun getDeviceInfo(): Map<String, Any> = wildEdge.trace("get_device_info", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("get_device_info")
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val freeStorageMb = Environment.getDataDirectory().freeSpace / 1_048_576

        mapOf(
            "model" to Build.MODEL,
            "android_version" to Build.VERSION.RELEASE,
            "battery_percent" to batteryPct,
            "free_storage_mb" to freeStorageMb,
        )
    }

    // -- Memory --

    @Tool(description = "Stores a value under a key so it can be recalled later.")
    fun remember(
        @ToolParam(description = "The key to store the value under.") key: String,
        @ToolParam(description = "The value to remember.") value: String,
    ): String = wildEdge.trace("remember", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("remember")
        prefs.edit().putString(key, value).apply()
        "Remembered \"$key\"."
    }

    @Tool(description = "Recalls a value that was previously remembered by key.")
    fun recall(
        @ToolParam(description = "The key to look up.") key: String,
    ): String = wildEdge.trace("recall", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("recall")
        prefs.getString(key, null) ?: "Nothing remembered for \"$key\"."
    }

    @Tool(description = "Lists all keys that have been remembered.")
    fun listMemories(): List<String> = wildEdge.trace("list_memories", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("list_memories")
        prefs.all.keys.toList()
    }

    @Tool(description = "Forgets a previously remembered value.")
    fun forget(
        @ToolParam(description = "The key to forget.") key: String,
    ): String = wildEdge.trace("forget", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("forget")
        prefs.edit().remove(key).apply()
        "Forgotten \"$key\"."
    }

    // -- Notes --

    @Tool(description = "Creates or overwrites a note with the given title and content.")
    fun createNote(
        @ToolParam(description = "The note title (used as the filename).") title: String,
        @ToolParam(description = "The full text content of the note.") content: String,
    ): String = wildEdge.trace("create_note", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("create_note")
        noteFile(title).writeText(content)
        "Note \"$title\" saved."
    }

    @Tool(description = "Lists the titles of all saved notes.")
    fun listNotes(): List<String> = wildEdge.trace("list_notes", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("list_notes")
        notesDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    @Tool(description = "Reads and returns the content of a note by title.")
    fun readNote(
        @ToolParam(description = "The title of the note to read.") title: String,
    ): String = wildEdge.trace("read_note", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("read_note")
        val file = noteFile(title)
        if (file.exists()) file.readText() else "No note found with title \"$title\"."
    }

    @Tool(description = "Deletes a note by title.")
    fun deleteNote(
        @ToolParam(description = "The title of the note to delete.") title: String,
    ): String = wildEdge.trace("delete_note", SpanKind.Tool, parent = currentTurnSpan) {
        onToolCall("delete_note")
        if (noteFile(title).delete()) "Note \"$title\" deleted."
        else "No note found with title \"$title\"."
    }

    private fun noteFile(title: String) = File(notesDir, "$title.txt")
}
