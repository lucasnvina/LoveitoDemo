package com.loveito.demo

import android.app.Application
import com.google.firebase.FirebaseApp

class LoveitoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
