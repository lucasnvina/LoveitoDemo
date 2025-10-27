package com.loveito.demo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.loveito.demo.flow.TriageEngineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Fire-and-forget update of triage engine; splash delay keeps UX simple
        CoroutineScope(Dispatchers.IO).launch {
            try { TriageEngineStore.ensureLatest(this@SplashActivity) } catch (_: Exception) {}
        }

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000) // 2 segundos
    }
}
