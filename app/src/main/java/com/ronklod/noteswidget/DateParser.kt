package com.ronklod.noteswidget

import java.util.Calendar

data class ParseResult(
    val noteText: String,
    val dueDate: Long?,
    val isList: Boolean = false,
    val listItems: List<String>? = null
)

object DateParser {

    // Ordered most-specific first so "due date" matches before bare "due"
    private val TRIGGERS = listOf(
        "תזכורת:", "תזכורת", "תזכיר לי:", "תזכיר לי",
        "אל תשכח:", "אל תשכח", "לא לשכוח:", "לא לשכוח",
        "מועד יעד:", "מועד יעד", "מועד:", "מועד",
        "עד תאריך:", "עד תאריך",
        "due date:", "due date",
        "reminder:", "reminder",
        "due:", "due"
    )

    // Ordered most-specific first to avoid partial matches
    private val LIST_TRIGGERS = listOf(
        "רשימת קניות", "רשימת משימות", "רשימת עשה",
        "רשימה", "רשימת",
        "לקנות", "לרכוש", "קניות",
        "קנה", "תרכוש", "תביא", "להביא",
        "shopping list", "shopping", "list:"
    )

    fun parse(speech: String): ParseResult {
        val lower = speech.lowercase()

        // 1. Check for explicit reminder triggers
        var dueDate: Long? = null
        var noteText = speech
        for (trigger in TRIGGERS) {
            val idx = lower.indexOf(trigger)
            if (idx < 0) continue
            val beforeTrigger = speech.substring(0, idx).trim()
            val afterTrigger  = speech.substring(idx + trigger.length).trim()
            val date = parseDateExpression(afterTrigger) ?: continue
            dueDate  = date
            noteText = beforeTrigger.ifEmpty { speech }
            break
        }

        // 2. Check for list pattern
        val items = tryParseList(speech, lower)
        if (items != null) {
            return ParseResult(noteText, dueDate, true, items)
        }

        // 3. If no explicit reminder found, try contextual date detection
        if (dueDate == null) {
            dueDate = parseDateExpression(lower)
        }

        return ParseResult(noteText, dueDate)
    }

    // ── List detection ────────────────────────────────────────────────────────

    private fun tryParseList(speech: String, lower: String): List<String>? {
        val hasTrigger = LIST_TRIGGERS.any { it in lower }

        // Extract the portion of text that should contain the list items
        var itemsText = speech
        if (hasTrigger) {
            for (trigger in LIST_TRIGGERS) {
                val idx = lower.indexOf(trigger)
                if (idx >= 0) {
                    val after = speech.substring(idx + trigger.length).trimStart(':', ' ', ',', '،')
                    if (after.isNotBlank()) { itemsText = after; break }
                }
            }
        }

        val items = extractItems(itemsText)
        return when {
            hasTrigger && items.size >= 2 -> items
            !hasTrigger && items.size >= 3 -> items  // 3+ comma-separated items = implicit list
            else -> null
        }
    }

    private fun extractItems(text: String): List<String> {
        // Try comma-separated first (most reliable)
        val commaParts = text.split(Regex("[,،]"))
        if (commaParts.size >= 2) {
            return commaParts
                .flatMap { splitByHebAnd(it.trim()) }
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 1 }
        }

        // Try Hebrew "ו" connectors: "חלב ולחם וביצים"
        val andParts = splitByHebAnd(text)
        if (andParts.size >= 2) {
            return andParts.map { it.trim() }.filter { it.isNotBlank() && it.length > 1 }
        }

        return emptyList()
    }

    // Split on a space followed by the Hebrew prefix ו (and), e.g. "חלב ולחם"
    private fun splitByHebAnd(text: String): List<String> =
        text.split(Regex("""\s+ו[-]?(?=[א-ת\d])"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    // ── Date / time parsing ───────────────────────────────────────────────────

    // Exposed for unit tests
    fun parseDateExpression(expr: String): Long? {
        if (expr.isBlank()) return null
        val lower = expr.lowercase().trim()
        val cal   = Calendar.getInstance()

        // ── RELATIVE TIME ─────────────────────────────────────────────────
        // Hebrew fixed forms
        if ("בעוד שעתיים"    in lower) { cal.add(Calendar.HOUR_OF_DAY, 2);  return cal.timeInMillis }
        if ("בעוד שעה"       in lower) { cal.add(Calendar.HOUR_OF_DAY, 1);  return cal.timeInMillis }
        if ("בעוד חצי שעה"  in lower) { cal.add(Calendar.MINUTE, 30);       return cal.timeInMillis }
        if ("בעוד רבע שעה"  in lower) { cal.add(Calendar.MINUTE, 15);       return cal.timeInMillis }

        // Hebrew: "בעוד X שעות/דקות"
        Regex("""בעוד\s+(\d+)\s+(שעות?|דקות?)""").find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            if (m.groupValues[2].startsWith("שעה") || m.groupValues[2].startsWith("שעות"))
                cal.add(Calendar.HOUR_OF_DAY, n) else cal.add(Calendar.MINUTE, n)
            return cal.timeInMillis
        }

        // English: "in X hours/minutes"
        Regex("""in\s+(\d+)\s+(hours?|minutes?)""").find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            if ("hour" in m.groupValues[2]) cal.add(Calendar.HOUR_OF_DAY, n)
            else cal.add(Calendar.MINUTE, n)
            return cal.timeInMillis
        }

        // ── BASE DAY ──────────────────────────────────────────────────────
        var daySet = false
        when {
            "מחרתיים" in lower || "the day after tomorrow" in lower -> {
                cal.add(Calendar.DAY_OF_YEAR, 2); daySet = true
            }
            "מחר"  in lower || "tomorrow" in lower -> {
                cal.add(Calendar.DAY_OF_YEAR, 1); daySet = true
            }
            "היום" in lower || "today"    in lower -> daySet = true
            else -> {
                val nextWeek = "שבוע הבא" in lower || "next " in lower
                daySet = applyWeekday(cal, lower, nextWeek)
            }
        }
        if (!daySet) return null

        // ── TIME ──────────────────────────────────────────────────────────
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // HH:MM (24h or 12h)
        val colonTime = Regex("""(\d{1,2}):(\d{2})""").find(lower)
        if (colonTime != null) {
            cal.set(Calendar.HOUR_OF_DAY, colonTime.groupValues[1].toInt())
            cal.set(Calendar.MINUTE,      colonTime.groupValues[2].toInt())
        } else {
            // "at X am/pm"  or  "ב-X" / "בשעה X"
            val hourMatch = Regex("""(?:at|ב-?|בשעה)\s*(\d{1,2})\s*(am|pm)?""").find(lower)
            if (hourMatch != null) {
                var h = hourMatch.groupValues[1].toInt()
                val ap = hourMatch.groupValues[2]
                if (ap == "pm" && h < 12) h += 12
                if (ap == "am" && h == 12) h = 0
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, 0)
            } else {
                // Named time-of-day
                when {
                    "בבוקר"          in lower || "morning"   in lower -> { cal.set(Calendar.HOUR_OF_DAY, 9);  cal.set(Calendar.MINUTE, 0) }
                    "בצהריים"        in lower || "noon"      in lower -> { cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0) }
                    "אחרי הצהריים"  in lower || "afternoon" in lower -> { cal.set(Calendar.HOUR_OF_DAY, 15); cal.set(Calendar.MINUTE, 0) }
                    "בערב"           in lower || "evening"   in lower -> { cal.set(Calendar.HOUR_OF_DAY, 19); cal.set(Calendar.MINUTE, 0) }
                    "בלילה"          in lower || "night"     in lower -> { cal.set(Calendar.HOUR_OF_DAY, 21); cal.set(Calendar.MINUTE, 0) }
                    else -> { cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0) } // default 09:00
                }
            }
        }

        // If computed time is already past, bump by one day
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return cal.timeInMillis
    }

    private fun applyWeekday(cal: Calendar, lower: String, forceNext: Boolean): Boolean {
        val days = mapOf(
            "ראשון"     to Calendar.SUNDAY,
            "שני"       to Calendar.MONDAY,
            "שלישי"    to Calendar.TUESDAY,
            "רביעי"    to Calendar.WEDNESDAY,
            "חמישי"    to Calendar.THURSDAY,
            "שישי"     to Calendar.FRIDAY,
            "שבת"      to Calendar.SATURDAY,
            "sunday"    to Calendar.SUNDAY,
            "monday"    to Calendar.MONDAY,
            "tuesday"   to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday"  to Calendar.THURSDAY,
            "friday"    to Calendar.FRIDAY,
            "saturday"  to Calendar.SATURDAY
        )
        for ((name, day) in days) {
            if (name in lower) {
                val today = cal.get(Calendar.DAY_OF_WEEK)
                var diff = day - today
                if (diff < 0 || (diff == 0 && forceNext)) diff += 7
                cal.add(Calendar.DAY_OF_YEAR, diff)
                return true
            }
        }
        return false
    }
}
