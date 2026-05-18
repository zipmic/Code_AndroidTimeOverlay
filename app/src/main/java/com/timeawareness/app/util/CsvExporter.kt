package com.timeawareness.app.util

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.util.Locale

object CsvExporter {

    fun buildCsv(
        history: Map<String, Map<LocalDate, Long>>,
        labelFor: (String) -> String,
    ): String = buildString {
        appendLine("Date,App,Package,Seconds,Hours")
        history
            .flatMap { (pkg, dates) ->
                dates.entries.map { (date, secs) -> Triple(date, pkg, secs) }
            }
            .sortedWith(
                compareByDescending<Triple<LocalDate, String, Long>> { it.first }
                    .thenBy { labelFor(it.second).lowercase() }
            )
            .forEach { (date, pkg, secs) ->
                val hours = String.format(Locale.ROOT, "%.2f", secs / 3600.0)
                appendLine("$date,${escape(labelFor(pkg))},${escape(pkg)},$secs,$hours")
            }
    }

    fun writeToCache(context: Context, csv: String): File {
        val dir  = File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = File(dir, "time_awareness_history.csv")
        file.writeText(csv, Charsets.UTF_8)
        return file
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\""
        else value
}
