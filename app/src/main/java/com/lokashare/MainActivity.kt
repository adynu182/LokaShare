package com.lokashare

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lokashare.data.local.TrackingDatabase
import com.lokashare.service.TrackingForegroundService
import com.lokashare.theme.AccentBlue
import com.lokashare.theme.ActiveGreen
import com.lokashare.theme.CardBg
import com.lokashare.theme.ChargingCoral
import com.lokashare.theme.DarkBg
import com.lokashare.theme.GlassBorder
import com.lokashare.theme.LokaShareTheme
import com.lokashare.theme.PendingAmber
import com.lokashare.theme.TextPrimary
import com.lokashare.theme.TextSecondary
import com.lokashare.util.BatteryHelper
import com.lokashare.util.PrefsManager
import com.lokashare.worker.TrackingWorker
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsManager(applicationContext)

        // Inisialisasi Logging (Timber)
        if (timber.log.Timber.forest().isEmpty()) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }

        enableEdgeToEdge()
        setContent {
            LokaShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBg
                ) {
                    LokaShareApp(
                        prefs = prefs,
                        onStartService = { startTrackingService() },
                        onStopService = { stopTrackingService() }
                    )
                }
            }
        }
    }

    private fun startTrackingService() {
        try {
            // Jalankan service utama
            val serviceIntent = Intent(this, TrackingForegroundService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)

            // Jadwalkan watchdog periodic via WorkManager (setiap 15 menit)
            val watchdogRequest = PeriodicWorkRequestBuilder<TrackingWorker>(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "tracking_watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                watchdogRequest
            )
            Toast.makeText(this, "Pelacakan GPS aktif di latar belakang", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menjalankan pelacakan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopTrackingService() {
        try {
            // Hentikan service utama
            val serviceIntent = Intent(this, TrackingForegroundService::class.java)
            stopService(serviceIntent)

            // Batalkan watchdog
            WorkManager.getInstance(applicationContext).cancelUniqueWork("tracking_watchdog")
            Toast.makeText(this, "Pelacakan GPS dinonaktifkan", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menonaktifkan pelacakan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun LokaShareApp(
    prefs: PrefsManager,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var userName by remember { mutableStateOf(prefs.getUserName()) }
    var isTrackingEnabled by remember { mutableStateOf(prefs.isTrackingEnabled()) }
    var showNameDialog by remember { mutableStateOf(userName == null) }
    var showBackgroundExplanation by remember { mutableStateOf(false) }

    // Live preferences listener
    DisposableEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("lokashare_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "tracking_enabled") {
                isTrackingEnabled = prefs.isTrackingEnabled()
            } else if (key == "user_name") {
                userName = prefs.getUserName()
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Database flow for pending count (Room DB)
    val database = remember { TrackingDatabase.getInstance(context) }
    val pendingCountFlow = remember { database.pendingLocationDao().getPendingCountFlow() }
    val pendingCount by pendingCountFlow.collectAsState(initial = 0)

    // Dynamic battery status
    var batteryPercentage by remember { mutableIntStateOf(BatteryHelper.getBatteryStatus(context).percentage) }
    var isBatteryCharging by remember { mutableStateOf(BatteryHelper.getBatteryStatus(context).isCharging) }

    // Periodically update battery
    LaunchedEffect(Unit) {
        while (true) {
            val stats = BatteryHelper.getBatteryStatus(context)
            batteryPercentage = stats.percentage
            isBatteryCharging = stats.isCharging
            delay(30000L) // Cek setiap 30 detik
        }
    }

    // Permission request launcher
    // Ref holder diperlukan agar launcher bisa direferensikan dari dalam callback-nya sendiri
    val fineLocationLauncherRef = remember { mutableStateOf<ActivityResultLauncher<Array<String>>?>(null) }

    val fineLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val activityGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACTIVITY_RECOGNITION] == true
        } else true

        if (!fineGranted && !coarseGranted) {
            Toast.makeText(context, "Aplikasi membutuhkan izin GPS untuk berfungsi", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !activityGranted) {
            Toast.makeText(context, "Aplikasi membutuhkan izin Activity Recognition untuk optimasi baterai", Toast.LENGTH_SHORT).show()
            fineLocationLauncherRef.value?.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            return@rememberLauncherForActivityResult
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBg) {
                showBackgroundExplanation = true
            } else {
                prefs.setTrackingEnabled(true)
                onStartService()
            }
        } else {
            prefs.setTrackingEnabled(true)
            onStartService()
        }
    }
    // Assign ref setelah launcher dibuat agar callback dapat menggunakannya
    fineLocationLauncherRef.value = fineLocationLauncher

    // Background Location request launcher (Android 10+)
    val bgLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            prefs.setTrackingEnabled(true)
            onStartService()
        } else {
            Toast.makeText(
                context,
                "Latar belakang GPS ditolak. Pelacakan hanya berjalan saat aplikasi terbuka.",
                Toast.LENGTH_LONG
            ).show()
            // Tetap nyalakan service (akan berjalan terbatas sesuai pembatasan OS)
            prefs.setTrackingEnabled(true)
            onStartService()
        }
    }

    // Notification Permission Launcher (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Request notification permission automatically on start if running Android 13+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Dialog Input Nama (First Time Startup)
    if (showNameDialog) {
        NameInputDialog(
            onSave = { name ->
                prefs.setUserName(name)
                showNameDialog = false
            }
        )
    }

    // Dialog Penjelasan Background Location
    if (showBackgroundExplanation) {
        BackgroundLocationExplanationDialog(
            onDismiss = {
                showBackgroundExplanation = false
                // Tetap coba jalankan dengan permission yang ada
                prefs.setTrackingEnabled(true)
                onStartService()
            },
            onConfirm = {
                showBackgroundExplanation = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        )
    }

    // Layout Utama
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // Header Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LokaShare 📍",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Power Saving & Durable Kill Tracker",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
            
            // Edit Name Button
            IconButton(
                onClick = { showNameDialog = true },
                modifier = Modifier
                    .background(GlassBorder, CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit Nama",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Halo Card / User Profile Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Profil Terdaftar",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = userName ?: "Belum Terdaftar",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Device ID: ",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = prefs.getDeviceId().take(12) + "...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Model: ",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = prefs.getDeviceModel(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Main Tracking Toggle Button
        TrackingToggleCard(
            isEnabled = isTrackingEnabled,
            onToggle = { enabled ->
                if (enabled) {
                    // Check permissions
                    val hasFine = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    val hasActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACTIVITY_RECOGNITION
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true

                    if (!hasFine && !hasCoarse) {
                        fineLocationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACTIVITY_RECOGNITION
                            )
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivity) {
                        fineLocationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACTIVITY_RECOGNITION
                            )
                        )
                    } else {
                        // Fine/Coarse Granted, check background location on API 29+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val hasBg = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasBg) {
                                showBackgroundExplanation = true
                            } else {
                                prefs.setTrackingEnabled(true)
                                onStartService()
                            }
                        } else {
                            prefs.setTrackingEnabled(true)
                            onStartService()
                        }
                    }
                } else {
                    prefs.setTrackingEnabled(false)
                    onStopService()
                }
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // LIVE STATS SECTION
        Text(
            text = "TELEMETRI LIVE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        // Last Sent Coordinates Card
        TelemetryCoordinateCard(prefs = prefs)

        Spacer(modifier = Modifier.height(12.dp))

        // Offline Queue Status Card
        TelemetryQueueCard(pendingCount = pendingCount)

        Spacer(modifier = Modifier.height(12.dp))

        // Battery Status Card
        TelemetryBatteryCard(
            percentage = batteryPercentage,
            isCharging = isBatteryCharging
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Battery Whitelist OEM Guide (Anti-Kill Section)
        AntiKillOemGuideCard(context = context)

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TrackingToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaScale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(
                1.dp,
                if (isEnabled) ActiveGreen.copy(alpha = 0.6f) else GlassBorder,
                RoundedCornerShape(24.dp)
            )
            .clickable { onToggle(!isEnabled) },
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) ActiveGreen.copy(alpha = 0.08f) else CardBg
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isEnabled) ActiveGreen.copy(alpha = 0.2f) else GlassBorder,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Default.Power else Icons.Default.PowerOff,
                        contentDescription = "Power State",
                        tint = if (isEnabled) ActiveGreen else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (isEnabled) "PELACAKAN AKTIF" else "PELACAKAN MATI",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) ActiveGreen else TextPrimary
                    )
                    Text(
                        text = if (isEnabled) "Bekerja di latar belakang (5m)" else "Tekan untuk mengaktifkan",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            if (isEnabled) {
                // Pulsing Green Indicator Dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .alpha(alphaScale)
                        .background(ActiveGreen, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(TextSecondary.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
    }
}

@Composable
fun TelemetryCoordinateCard(prefs: PrefsManager) {
    val locationData = remember { prefs.getLastSentLocation() }
    var lastSent by remember { mutableStateOf(locationData) }

    // Listen to changes in coordinates
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("lokashare_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "last_latitude_bits" || key == "last_longitude_bits") {
                lastSent = prefs.getLastSentLocation()
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AccentBlue.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "GPS",
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Posisi Terakhir Dikirim",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (lastSent != null) {
                    val (lat, lng, timestamp) = lastSent!!
                    Text(
                        text = String.format(Locale.US, "Lat: %.6f, Lng: %.6f", lat, lng),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Dikirim pada: " + formatTimestamp(timestamp),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        text = "Belum ada lokasi dikirim",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryQueueCard(pendingCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (pendingCount == 0) ActiveGreen.copy(alpha = 0.15f) else PendingAmber.copy(
                            alpha = 0.15f
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (pendingCount == 0) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                    contentDescription = "Koneksi & Queue",
                    tint = if (pendingCount == 0) ActiveGreen else PendingAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Antrian Offline (Room DB)",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (pendingCount == 0) {
                    Text(
                        text = "Semua Data Tersinkronisasi",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ActiveGreen
                    )
                } else {
                    Text(
                        text = "$pendingCount data lokasi tertunda",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PendingAmber
                    )
                    Text(
                        text = "Tersimpan lokal, akan di-sync saat online",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryBatteryCard(
    percentage: Int,
    isCharging: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ChargingCoral.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.Power,
                    contentDescription = "Baterai",
                    tint = ChargingCoral,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Persentase Baterai",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$percentage% ${if (isCharging) "(Mengisi Daya)" else "(Menggunakan Baterai)"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
fun AntiKillOemGuideCard(context: Context) {
    val manufacturer = remember { Build.MANUFACTURER.lowercase() }
    
    val guide = remember(manufacturer) {
        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("miui") -> {
                OemGuide(
                    brandName = "Xiaomi / Redmi (MIUI)",
                    steps = listOf(
                        "1. Aktifkan 'Mulai Otomatis' (Autostart) untuk LokaShare.",
                        "2. Buka Pengaturan Battery Saver -> LokaShare.",
                        "3. Pilih 'Tidak Ada Batasan' (No Restrictions) agar tidak di-kill."
                    )
                )
            }
            manufacturer.contains("samsung") -> {
                OemGuide(
                    brandName = "Samsung (One UI)",
                    steps = listOf(
                        "1. Masuk ke Pengaturan Baterai -> Batas Penggunaan Latar Belakang.",
                        "2. Masuk ke 'Aplikasi Tidak Pernah Tidur' (Never Sleeping Apps).",
                        "3. Tambahkan LokaShare ke dalam daftar tersebut."
                    )
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                OemGuide(
                    brandName = "Oppo / Realme (ColorOS)",
                    steps = listOf(
                        "1. Buka Info Aplikasi LokaShare -> Penggunaan Baterai.",
                        "2. Aktifkan 'Izinkan Aktivitas Latar Belakang' (Allow background activity).",
                        "3. Aktifkan 'Mulai Otomatis' di pengaturan Startup."
                    )
                )
            }
            manufacturer.contains("vivo") -> {
                OemGuide(
                    brandName = "Vivo (Funtouch OS)",
                    steps = listOf(
                        "1. Buka aplikasi iManager -> Pengelolaan Baterai.",
                        "2. Pilih Pengelolaan Konsumsi Daya Latar Belakang.",
                        "3. Whitelist LokaShare ke status 'Konsumsi Daya Tinggi Berkelanjutan'."
                    )
                )
            }
            manufacturer.contains("huawei") -> {
                OemGuide(
                    brandName = "Huawei (EMUI)",
                    steps = listOf(
                        "1. Masuk ke Pengaturan Baterai -> Peluncuran Aplikasi.",
                        "2. Cari LokaShare dan matikan opsi 'Kelola Otomatis'.",
                        "3. Pastikan 'Mulai Otomatis' dan 'Jalan di Latar Belakang' aktif."
                    )
                )
            }
            else -> {
                OemGuide(
                    brandName = "Android Standar (${Build.MANUFACTURER})",
                    steps = listOf(
                        "1. Buka Info Aplikasi LokaShare -> Baterai.",
                        "2. Atur setelan Optimasi Baterai menjadi 'Tidak Dibatasi' (Unrestricted).",
                        "3. Whitelist aplikasi dari pembatasan optimasi sistem."
                    )
                )
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder.copy(alpha = 0.7f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Perhatian",
                    tint = PendingAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PANDUAN ANTI-KILL (${guide.brandName})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PendingAmber,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Agar pelacakan handal dan tidak mudah dimatikan paksa oleh sistem HP Anda, harap ikuti petunjuk berikut:",
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            guide.steps.forEach { step ->
                Text(
                    text = step,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.padding(vertical = 2.dp),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    openBatteryOptimizationSettings(context)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GlassBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Pengaturan",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Buka Pengaturan Baterai",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private data class OemGuide(
    val brandName: String,
    val steps: List<String>
)

@SuppressLint("BatteryLife")
private fun openBatteryOptimizationSettings(context: Context) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    
    val intent = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            }
        }
        manufacturer.contains("samsung") -> {
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            }
        }
        manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
        }
        manufacturer.contains("vivo") -> {
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            }
        }
        else -> {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Fallback ke pengaturan aplikasi atau optimasi baterai standar
        try {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(fallbackIntent)
        } catch (ex: Exception) {
            // Ultimate fallback ke halaman detail aplikasi
            try {
                val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(appSettingsIntent)
            } catch (err: Exception) {
                Toast.makeText(context, "Gagal membuka pengaturan sistem", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameInputDialog(onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {}, // Force user to fill name on first start
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Selamat Datang! 👋",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "LokaShare membutuhkan Nama Anda untuk diidentifikasi di backend server saat melacak koordinat.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (it.trim().isNotEmpty()) isError = false
                    },
                    label = { Text("Masukkan Nama Anda") },
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = GlassBorder,
                        errorBorderColor = ChargingCoral,
                        focusedLabelColor = AccentBlue,
                        unfocusedLabelColor = TextSecondary
                    )
                )
                
                if (isError) {
                    Text(
                        text = "Nama tidak boleh kosong!",
                        color = ChargingCoral,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = {
                        if (text.trim().isEmpty()) {
                            isError = true
                        } else {
                            onSave(text.trim())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Mulai LokaShare",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun BackgroundLocationExplanationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(AccentBlue.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info Lokasi",
                        tint = AccentBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Akses Lokasi Latar Belakang",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "LokaShare membutuhkan akses lokasi sepanjang waktu (Allow all the time) agar aplikasi dapat memeriksa posisi GPS setiap 5 menit meskipun HP dalam keadaan mati/terkunci di saku Anda.\n\nData lokasi ini digunakan untuk melacak pergeseran > 200m dan dilaporkan ke Firestore secara otomatis.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Nanti Saja", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Buka Pengaturan", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss, d MMM yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
