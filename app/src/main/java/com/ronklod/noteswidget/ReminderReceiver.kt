package com.ronklod.noteswidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Replaced by Keep's native REMINDER_TIME intent extra — kept as empty stub to avoid removing the file.
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_NOTE = "note_text"
    }
    override fun onReceive(context: Context, intent: Intent) = Unit
}
