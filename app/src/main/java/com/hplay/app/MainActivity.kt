package com.hplay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.hplay.app.capture.HPlayService
import com.hplay.app.ui.HPlayTheme
import com.hplay.app.ui.MainScreen
import com.hplay.app.ui.MainViewModel
import com.hplay.updater.UpdateFlow
import com.hplay.updater.Updater

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Android 14+: el diálogo del sistema se muestra en cada sesión y su resultado vale una sola vez. */
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) HPlayService.mirror(this, r.resultCode, data, vm.selectedIp.value)
    }

    /** Se pide el micrófono (necesario para capturar el audio interno) y, se conceda o no, se sigue. */
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HPlayTheme { MainScreen(vm, onMirror = ::startMirror, onUpdate = ::showUpdate) } }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Al volver de los ajustes de «instalar apps» también se vuelve a comprobar.
        UpdateFlow.check(this) { state, m ->
            vm.update.value = if (state == Updater.State.NEWER) m else null
            if (state == Updater.State.FORCED && m != null) UpdateFlow.show(this, ASSET, m, forced = true)
        }
    }

    private fun showUpdate() {
        vm.update.value?.let { UpdateFlow.show(this, ASSET, it, forced = false) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun startMirror() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchProjection()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchProjection() {
        projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun handleIntent(i: Intent?) {
        val url = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { URL_IN_TEXT.find(it)?.value }
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        vm.handleShared(url)
    }

    companion object {
        private val URL_IN_TEXT = Regex("https?://\\S+")
        private const val ASSET = "HPlay.apk"
    }
}
