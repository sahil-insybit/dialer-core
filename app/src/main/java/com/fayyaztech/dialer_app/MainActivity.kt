package com.fayyaztech.dialer_app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fayyaztech.dialer_app.recording.CallRecorder
import com.fayyaztech.dialer_app.recording.Recording
import com.fayyaztech.dialer_app.recording.RecordingApi
import com.fayyaztech.dialer_core.utils.CallHelper
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val recorder by lazy { CallRecorder(this) }
    private lateinit var prefs: android.content.SharedPreferences

    // The number currently being recorded, so we can tag the upload.
    private var recordingNumber: String = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dialer_app", Context.MODE_PRIVATE)

        requestDialerPermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        initialApiUrl = prefs.getString("api_url", "https://call-recording-api.onrender.com") ?: "",
                        onSaveApiUrl = { prefs.edit().putString("api_url", it).apply() },
                        onCall = { placeCall(it) },
                        onSetDefaultDialer = { requestDefaultDialerRole() },
                        isRecording = { recorder.isRecording },
                        onToggleRecord = { number, apiUrl, onResult ->
                            toggleRecord(number, apiUrl, onResult)
                        },
                        onFetchList = { apiUrl, onResult -> fetchList(apiUrl, onResult) }
                    )
                }
            }
        }
    }

    // --- Recording -----------------------------------------------------------

    private fun toggleRecord(number: String, apiUrl: String, onResult: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestDialerPermissions()
            onResult("Microphone permission needed. Grant it, then try again.")
            return
        }

        if (!recorder.isRecording) {
            recordingNumber = number.ifBlank { "unknown" }
            setSpeakerphone(true) // helps the mic pick up the other side
            val started = recorder.start(recordingNumber)
            onResult(if (started) "Recording... press again to stop & upload." else "Failed to start recording.")
        } else {
            val result = recorder.stop()
            setSpeakerphone(false)
            if (result == null) {
                onResult("Nothing recorded (too short or mic busy).")
                return
            }
            onResult("Recorded ${result.durationMs / 1000}s. Uploading...")
            // Upload off the main thread.
            thread {
                try {
                    val api = RecordingApi(apiUrl.trimEnd('/'))
                    api.upload(
                        file = result.file,
                        phoneNumber = recordingNumber,
                        direction = "outgoing",
                        durationMs = result.durationMs
                    )
                    runOnUiThread { onResult("Uploaded successfully.") }
                } catch (e: Exception) {
                    runOnUiThread { onResult("Upload failed: ${e.message}") }
                }
            }
        }
    }

    private fun fetchList(apiUrl: String, onResult: (Result<List<Recording>>) -> Unit) {
        thread {
            try {
                val api = RecordingApi(apiUrl.trimEnd('/'))
                val list = api.list()
                runOnUiThread { onResult(Result.success(list)) }
            } catch (e: Exception) {
                runOnUiThread { onResult(Result.failure(e)) }
            }
        }
    }

    private fun setSpeakerphone(on: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        } catch (_: Exception) {
        }
    }

    // --- Calls / dialer role -------------------------------------------------

    private fun placeCall(number: String) {
        if (number.isBlank()) {
            toast("Enter a number first")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Call permission not granted yet")
            requestDialerPermissions()
            return
        }
        val ok = CallHelper(this).makeCall(number.trim())
        if (!ok) toast("Could not start the call")
    }

    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    toast("Already the default dialer")
                    return
                }
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else {
                toast("Dialer role not available on this device")
            }
        } else {
            val telecom = getSystemService(TelecomManager::class.java)
            if (telecom != null && packageName != telecom.defaultDialerPackage) {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                startActivity(intent)
            } else {
                toast("Already the default dialer")
            }
        }
    }

    private fun requestDialerPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

@Composable
private fun MainScreen(
    initialApiUrl: String,
    onSaveApiUrl: (String) -> Unit,
    onCall: (String) -> Unit,
    onSetDefaultDialer: () -> Unit,
    isRecording: () -> Boolean,
    onToggleRecord: (number: String, apiUrl: String, onResult: (String) -> Unit) -> Unit,
    onFetchList: (apiUrl: String, onResult: (Result<List<Recording>>) -> Unit) -> Unit
) {
    var number by remember { mutableStateOf("") }
    var apiUrl by remember { mutableStateOf(initialApiUrl) }
    var status by remember { mutableStateOf("") }
    var recordingLabel by remember { mutableStateOf(if (isRecording()) "Stop & upload" else "Record") }
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Dialer App", fontSize = 26.sp)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = number,
            onValueChange = { number = it.filter { c -> c.isDigit() || c in "+*#" } },
            label = { Text("Phone number") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        val digits = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        digits.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowItems.forEach { d ->
                    OutlinedButton(onClick = { number += d }) {
                        Text(d, fontSize = 18.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(10.dp))
        Button(onClick = { onCall(number) }, modifier = Modifier.fillMaxWidth()) {
            Text("Call")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSetDefaultDialer, modifier = Modifier.fillMaxWidth()) {
            Text("Set as default dialer")
        }

        Spacer(Modifier.height(20.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        Text("Call recording", fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it; onSaveApiUrl(it) },
            label = { Text("API URL (e.g. http://192.168.1.42:3001)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                onToggleRecord(number, apiUrl) { msg -> status = msg }
                recordingLabel = if (isRecording()) "Stop & upload" else "Record"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(recordingLabel)
        }
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                status = "Loading recordings..."
                onFetchList(apiUrl) { result ->
                    result
                        .onSuccess { recordings = it; status = "Loaded ${it.size} recording(s)." }
                        .onFailure { status = "Could not load: ${it.message}" }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh recordings")
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(14.dp))
        recordings.forEach { r ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${r.phoneNumber}  •  ${r.durationMs / 1000}s", fontSize = 14.sp)
                Text(r.createdAt, fontSize = 11.sp)
                Text(r.url, fontSize = 11.sp)
                Divider(modifier = Modifier.padding(top = 6.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Note: on Android 10+ apps can't capture true two-way call audio. " +
                "This records the mic. Use speaker to catch the other side.",
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}
