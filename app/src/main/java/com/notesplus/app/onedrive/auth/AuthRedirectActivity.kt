package com.notesplus.app.onedrive.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.notesplus.app.MainActivity
import com.notesplus.app.NotesPlusApp
import kotlinx.coroutines.launch

/** Invisible activity that catches notesplus://auth and hands the code to [AuthManager]. */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val app = application as NotesPlusApp
        if (uri != null) {
            app.appScope.launch {
                app.container.authManager.handleRedirect(uri)
                app.container.syncScheduler.requestSync(delayMs = 0L, force = true)
            }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
