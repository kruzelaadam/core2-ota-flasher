package cz.adamkruzela.core2ota

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import cz.adamkruzela.core2ota.mds.MdsEngine
import cz.adamkruzela.core2ota.ui.FlasherScreen
import cz.adamkruzela.core2ota.ui.FlasherViewModel
import java.io.File
import java.nio.charset.Charset

class MainActivity : ComponentActivity() {
    private val vm: FlasherViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = hasBtPermission()
            vm.onPermissionsResult(granted)
            if (granted) {
                ensureBtAndRefresh()
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.refreshDevices()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        MdsEngine.ensureLoaded(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBtPermissions()
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFF1A4A5C),
                        onPrimary = Color.White,
                        secondary = Color(0xFF5C4A32),
                        onSecondary = Color.White,
                        background = Color(0xFFF3EEE4),
                        onBackground = Color(0xFF0B1C24),
                        surface = Color(0xFFFFFBF5),
                        onSurface = Color(0xFF0B1C24),
                        surfaceContainerHighest = Color(0xFFFFFBF5),
                        onSurfaceVariant = Color(0xFF3D4A52),
                        outline = Color(0xFF7A6A58),
                    ),
            ) {
                val state by vm.state.collectAsState()
                FlasherScreen(
                    state = state,
                    onSelectDevice = vm::selectDevice,
                    onMode = vm::setMode,
                    onRefreshCatalog = vm::reloadCurrentCatalog,
                    onSelectFirmware = vm::selectFirmware,
                    onSelectModified = vm::selectModified,
                    onFlash = vm::startFlash,
                    onSecretMenu = { vm.startSecretMenu(exitToNormal = false) },
                    onExitSecretMenu = { vm.startSecretMenu(exitToNormal = true) },
                    onCancel = vm::cancelFlash,
                    onCopyCommLog = { copyCommLog(state.commLogPath) },
                    onEngine = vm::setEngine,
                    onRestartApp = ::restartApp,
                    onOpenSuuntoAppInfo = ::openSuuntoAppInfo,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshEngine()
        if (hasBtPermission()) {
            vm.onPermissionsResult(true)
            ensureBtAndRefresh()
        }
    }

    private fun requestBtPermissions() {
        val missing =
            buildList {
                if (!hasBtPermission()) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        if (missing.isEmpty()) {
            vm.onPermissionsResult(true)
            ensureBtAndRefresh()
            return
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasBtPermission(): Boolean {
        val connect =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        val scan =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        return connect && scan
    }

    private fun ensureBtAndRefresh() {
        val intent = vm.ensureBluetoothOn()
        if (intent != null) {
            enableBtLauncher.launch(intent)
        } else {
            vm.refreshDevices()
        }
    }

    private fun readCommLog(path: String?): Pair<File, String>? {
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "No communication log yet — run a flash first.", Toast.LENGTH_SHORT)
                .show()
            return null
        }
        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(this, "Log file missing: $path", Toast.LENGTH_LONG).show()
            return null
        }
        val text = file.readText(Charset.forName("UTF-8"))
        if (text.isBlank()) {
            Toast.makeText(this, "Log file is empty: $path", Toast.LENGTH_LONG).show()
            return null
        }
        return file to text
    }

    private fun copyCommLog(path: String?) {
        val (file, full) = readCommLog(path) ?: return
        val text = if (full.length > CLIPBOARD_LIMIT) condenseReadRuns(full) else full
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val copied =
            runCatching {
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Core2 OTA MDS log ${file.name}", text),
                )
            }
        val msg =
            when {
                copied.isFailure -> "Log too large for the clipboard — file: ${file.absolutePath}"
                text.length < full.length -> "Copied log (long READ runs shortened; full file: ${file.name})"
                else -> "Copied full log (${text.length} chars) to clipboard"
            }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Keeps the first and last READ lines of each run; the file on disk keeps them all. */
    private fun condenseReadRuns(text: String): String {
        val out = StringBuilder()
        val run = ArrayList<String>()

        fun flush() {
            if (run.size <= 8) {
                run.forEach { out.append(it).append('\n') }
            } else {
                run.take(4).forEach { out.append(it).append('\n') }
                out.append("… ${run.size - 8} READ lines omitted …\n")
                run.takeLast(4).forEach { out.append(it).append('\n') }
            }
            run.clear()
        }
        for (line in text.lineSequence()) {
            if (line.contains("] READ type=")) {
                run += line
            } else {
                flush()
                out.append(line).append('\n')
            }
        }
        flush()
        return out.toString()
    }

    private fun openSuuntoAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", MdsEngine.SUUNTO_PACKAGE, null)),
        )
    }

    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val CLIPBOARD_LIMIT = 200_000
    }
}
