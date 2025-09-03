package com.loveito.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
