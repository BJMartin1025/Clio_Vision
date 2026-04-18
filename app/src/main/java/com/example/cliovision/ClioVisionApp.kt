package com.example.cliovision

import androidx.multidex.MultiDexApplication
import com.meta.wearable.dat.core.Wearables

class ClioVisionApp : MultiDexApplication(){
    override fun onCreate() {
        super.onCreate()
        // Initialize once per process, immediately on startup
        Wearables.initialize(applicationContext)
    }
}