package com.ronklod.noteswidget

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceNoteActivity : Activity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var isListening = false

    companion object {
        private const val PERMISSION_AUDIO = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_note)

        findViewById<View>(R.id.overlay_bg).setOnClickListener { cancelAndFinish() }
        findViewById<View>(R.id.stop_btn).setOnClickListener   { stopAndSave() }
        findViewById<View>(R.id.cancel_btn).setOnClickListener { cancelAndFinish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_AUDIO
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else { Toast.makeText(this, R.string.error_permission, Toast.LENGTH_LONG).show(); finish() }
        }
    }

    // ── Speech recognition ───────────────────────────────────────────────────

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.error_no_speech, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        isListening = true
        startPulse()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                val scale = 0.85f + (rmsdB + 2f).coerceIn(0f, 12f) / 12f * 0.55f
                findViewById<ImageView>(R.id.mic_icon)?.let { it.scaleX = scale; it.scaleY = scale }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                findViewById<TextView>(R.id.partial_text)?.text = partial
            }

            override fun onEndOfSpeech() {
                isListening = false
                stopPulse()
                setStatus(R.string.status_processing)
            }

            override fun onError(error: Int) {
                isListening = false
                val msgRes = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.error_no_match
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.error_permission
                    else -> R.string.error_recognition
                }
                Toast.makeText(this@VoiceNoteActivity, msgRes, Toast.LENGTH_SHORT).show()
                finish()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()

                if (text.isNullOrEmpty()) {
                    Toast.makeText(this@VoiceNoteActivity, R.string.error_empty_result, Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }

                val parsed = DateParser.parse(text)
                saveToKeep(parsed.noteText, parsed.dueDate, parsed.isList, parsed.listItems)
                finish()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Auto-stop after 1.5 s of silence so the user doesn't need to tap Stop
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        speechRecognizer?.startListening(intent)
    }

    // ── Keep integration ─────────────────────────────────────────────────────

    private fun saveToKeep(
        noteText: String,
        dueDate: Long?,
        isList: Boolean = false,
        listItems: List<String>? = null
    ) {
        val bodyText = if (isList && !listItems.isNullOrEmpty()) listItems.joinToString("\n") else noteText
        val fallbackText = if (dueDate != null) "$bodyText\n\n⏰ ${formatDate(dueDate)}" else bodyText

        val showToast: () -> Unit = {
            if (isList && !listItems.isNullOrEmpty()) showListSavedToast(listItems)
            else showSavedToast(noteText, dueDate)
        }

        // CREATE_NOTE accepts REMINDER_TIME so Keep sets its native bell reminder
        val createIntent = Intent("com.google.android.keep.action.CREATE_NOTE").apply {
            setPackage("com.google.android.keep")
            putExtra(Intent.EXTRA_TEXT, bodyText)
            if (isList) putExtra("com.google.android.keep.extra.IS_LIST", true)
            dueDate?.let { putExtra("com.google.android.keep.extra.REMINDER_TIME", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(createIntent)
            showToast()
            return
        } catch (_: ActivityNotFoundException) { }

        // Fallback: ACTION_SEND — reminder not supported via this path, so append the time as text
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.google.android.keep")
            putExtra(Intent.EXTRA_TEXT, fallbackText)
            if (isList) putExtra("com.google.android.keep.extra.IS_LIST", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(sendIntent)
            showToast()
            return
        } catch (_: ActivityNotFoundException) { }

        // Last resort: system share sheet
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, fallbackText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    getString(R.string.share_chooser_title)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSavedToast(noteText: String, dueDate: Long?) {
        val msg = if (dueDate != null)
            getString(R.string.note_saved_with_reminder, formatDate(dueDate))
        else
            getString(R.string.note_saved, noteText.take(40))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showListSavedToast(items: List<String>) {
        Toast.makeText(this, getString(R.string.note_saved_as_list, items.size), Toast.LENGTH_SHORT).show()
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(millis))

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun startPulse() {
        val mic = findViewById<ImageView>(R.id.mic_icon) ?: return
        pulseAnimator = ObjectAnimator.ofFloat(mic, "alpha", 1f, 0.3f).apply {
            duration = 700
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel(); pulseAnimator = null
        val mic = findViewById<ImageView>(R.id.mic_icon)
        mic?.alpha = 1f; mic?.scaleX = 1f; mic?.scaleY = 1f
    }

    private fun setStatus(resId: Int) {
        findViewById<TextView>(R.id.status_text)?.setText(resId)
    }

    private fun stopAndSave() {
        if (isListening) {
            isListening = false
            stopPulse()
            setStatus(R.string.status_processing)
            findViewById<View>(R.id.stop_btn).isEnabled = false
            speechRecognizer?.stopListening()
        }
    }

    private fun cancelAndFinish() {
        speechRecognizer?.cancel()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (isListening) speechRecognizer?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
