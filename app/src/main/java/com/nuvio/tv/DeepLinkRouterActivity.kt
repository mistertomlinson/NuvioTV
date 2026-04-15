package com.nuvio.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

class DeepLinkRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri?.scheme == "nuvio") {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(mainIntent)
        }
        finish()
    }
}
