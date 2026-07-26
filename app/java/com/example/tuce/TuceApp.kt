package com.example.tuce

import android.app.Application
import android.content.Context

class TuceApp : Application() {
    companion object {
        lateinit var ctx: Context
    }
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }
}