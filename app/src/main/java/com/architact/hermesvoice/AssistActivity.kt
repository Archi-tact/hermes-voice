package com.architact.hermesvoice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry point when Hermes is the default digital assistant: side-button long press (set to
 * "digital assistant"), home long press, or a headset/earbud voice command. It forwards to the
 * main screen, which starts listening right away, and disappears without a trace.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
