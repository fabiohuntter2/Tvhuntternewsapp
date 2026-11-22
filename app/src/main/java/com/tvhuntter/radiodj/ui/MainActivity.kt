package com.tvhuntter.radiodj.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.tvhuntter.radiodj.audio.RadioEngine
import com.tvhuntter.radiodj.service.RadioService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == false) {
            Toast.makeText(this, "Sem permissão de microfone não dá para falar no ar", Toast.LENGTH_LONG).show()
        }
        // reinicia o serviço já com o tipo "microfone" liberado
        RadioService.start(this, RadioService.ACTION_REFRESH_TYPE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioEngine.init(this)
        RadioService.start(this)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())

        lifecycleScope.launch {
            RadioEngine.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
        }

        setContent {
            RadioTheme {
                AppScreen(onExit = {
                    RadioService.start(this, RadioService.ACTION_EXIT)
                    finishAndRemoveTask()
                })
            }
        }
    }
}
