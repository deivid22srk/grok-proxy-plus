package com.deivid22srk.grokproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.deivid22srk.grokproxy.ui.GrokProxyTheme
import com.deivid22srk.grokproxy.ui.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Boot the Go runtime early: pass the app's private files dir as the
        // data root so accounts/settings/usage survive across launches.
        val dataDir = filesDir.absolutePath
        setContent {
            GrokProxyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var bridgeReady by remember { mutableStateOf(false) }
                    var initError by remember { mutableStateOf<String?>(null) }
                    if (!bridgeReady) {
                        // One-shot init on first composition.
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            if (Bridge.ensureLoaded()) {
                                val err = Bridge.errorMessage(Bridge.nativeInit(dataDir))
                                if (err != null) initError = err else bridgeReady = true
                            }
                        }
                        // Loading splash while the native lib comes up.
                        com.deivid22srk.grokproxy.ui.LoadingSplash(
                            error = initError ?: Bridge.lastError,
                        )
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Best-effort: stop the proxy when the process is torn down. The Go
        // runtime also registers a destructor via c-shared, so this is belt and
        // braces.
        try {
            if (Bridge.ensureLoaded()) Bridge.nativeStopServer()
        } catch (_: Throwable) { /* ignore on shutdown */ }
    }
}
