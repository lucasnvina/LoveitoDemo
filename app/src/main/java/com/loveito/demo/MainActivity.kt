package com.loveito.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            val start = if (FirebaseAuth.getInstance().currentUser == null) {
                AuthFragment()
            } else {
                HomeFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_host, start)
                .commitNow()
        }
    }
}
