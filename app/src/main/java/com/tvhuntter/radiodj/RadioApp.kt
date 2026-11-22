package com.tvhuntter.radiodj

import android.app.Application
import com.tvhuntter.radiodj.audio.RadioEngine

class RadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RadioEngine.init(this)
    }
}
