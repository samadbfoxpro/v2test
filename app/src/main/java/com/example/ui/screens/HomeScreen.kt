package com.example.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ConnectionStatus
import com.example.data.model.SmartConnectMode
import com.example.ui.components.SettingsBottomSheet
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RoseError
import com.example.ui.viewmodel.VpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VpnViewModel,
    onNavigateToServers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val speedStats by viewModel.speedStats.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val dnsProvider by viewModel.dnsProvider.collectAsStateWithLifecycle()
    val muxEnabled by viewModel.muxEnabled.collectAsStateWithLifecycle()
    val fragmentEnabled by viewModel.fragmentEnabled.collectAsStateWithLifecycle()
    val isServerSectionEnabled by viewModel.isServerSectionEnabled.collectAsStateWithLifecycle()
    val testUrl by viewModel.testUrl.collectAsStateWithLifecycle()

    val smartConnectMode by viewModel.smartConnectMode.collectAsStateWithLifecycle()
    val connectingStepMessage by viewModel.connectingStepMessage.collectAsStateWithLifecycle()

    val isLanSharingActive by viewModel.isLanSharingActive.collectAsStateWithLifecycle()
    val isLanHttpRunning by viewModel.isLanHttpRunning.collectAsStateWithLifecycle()
    val isLanSocksRunning by viewModel.isLanSocksRunning.collectAsStateWithLifecycle()
    val currentLanIp by viewModel.currentLanIp.collectAsStateWithLifecycle()
    val lanLastError by viewModel.lanLastError.collectAsStateWithLifecycle()
    val lanHttpEnabled by viewModel.lanHttpEnabled.collectAsStateWithLifecycle()
    val lanHttpPort by viewModel.lanHttpPort.collectAsStateWithLifecycle()
    val lanSocksEnabled by viewModel.lanSocksEnabled.collectAsStateWithLifecycle()
    val lanSocksPort by viewModel.lanSocksPort.collectAsStateWithLifecycle()
    val isHideConfigSharingEnabled by viewModel.isHideConfigSharingEnabled.collectAsStateWithLifecycle()

    val dnsDiagnosticsState by viewModel.dnsDiagnosticsState.collectAsStateWithLifecycle()
    val dnsBenchmarkResult by viewModel.dnsBenchmarkResult.collectAsStateWithLifecycle()
    val isDnsBenchmarking by viewModel.isDnsBenchmarking.collectAsStateWithLifecycle()
    val dnsMode by viewModel.dnsMode.collectAsStateWithLifecycle()
    val fakeDnsEnabled by viewModel.fakeDnsEnabled.collectAsStateWithLifecycle()

    // Dialog & Sheet States
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDnsDiagnosticsSheet by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionApproved()
        }
    }

    // Glowing Pulse Animation for connection button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val isPulseActive = connectionStatus == ConnectionStatus.CONNECTED ||
            connectionStatus == ConnectionStatus.CONNECTING ||
            connectionStatus == ConnectionStatus.SWITCHING ||
            connectionStatus == ConnectionStatus.TESTING_CONNECTION

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulseActive) 1.22f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (isPulseActive) 0.05f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CyanPrimary.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Shadow VPN",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = CyanPrimary.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "v0.1.8",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanPrimary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                            Text(
                                text = "مسیریاب امن هسته Xray",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Settings
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "تنظیمات",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val isSmallScreen = maxHeight < 680.dp || maxWidth < 380.dp
            val outerButtonSize = if (isSmallScreen) 145.dp else 175.dp
            val innerButtonSize = if (isSmallScreen) 110.dp else 135.dp
            val powerIconSize = if (isSmallScreen) 46.dp else 56.dp
            val verticalSpacing = if (isSmallScreen) 10.dp else 15.dp
            val horizontalPadding = if (isSmallScreen) 14.dp else 20.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = if (isSmallScreen) 6.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                // Main Connection Glow Power Button
                Box(
                    modifier = Modifier
                        .size(outerButtonSize)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (connectionStatus == ConnectionStatus.CONNECTED ||
                        connectionStatus == ConnectionStatus.CONNECTING ||
                        connectionStatus == ConnectionStatus.SWITCHING ||
                        connectionStatus == ConnectionStatus.TESTING_CONNECTION
                    ) {
                        val pulseColor = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> EmeraldSuccess
                            ConnectionStatus.SWITCHING -> ElectricViolet
                            ConnectionStatus.TESTING_CONNECTION -> CyanPrimary
                            else -> AmberWarning
                        }
                        Box(
                            modifier = Modifier
                                .size(outerButtonSize - 8.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(pulseColor.copy(alpha = pulseAlpha))
                        )
                    }

                    val buttonGradient = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> Brush.radialGradient(
                            colors = listOf(EmeraldSuccess, Color(0xFF059669))
                        )
                        ConnectionStatus.CONNECTING -> Brush.radialGradient(
                            colors = listOf(AmberWarning, Color(0xFFD97706))
                        )
                        ConnectionStatus.SWITCHING -> Brush.radialGradient(
                            colors = listOf(ElectricViolet, Color(0xFF7C3AED))
                        )
                        ConnectionStatus.TESTING_CONNECTION -> Brush.radialGradient(
                            colors = listOf(CyanPrimary, Color(0xFF0284C7))
                        )
                        ConnectionStatus.DISCONNECTING, ConnectionStatus.FAILED -> Brush.radialGradient(
                            colors = listOf(RoseError, Color(0xFFE11D48))
                        )
                        ConnectionStatus.DISCONNECTED -> Brush.radialGradient(
                            colors = listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                        )
                    }

                    val buttonBorderColor = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> EmeraldSuccess.copy(alpha = 0.9f)
                        ConnectionStatus.SWITCHING -> ElectricViolet.copy(alpha = 0.9f)
                        ConnectionStatus.TESTING_CONNECTION -> CyanPrimary.copy(alpha = 0.9f)
                        ConnectionStatus.CONNECTING -> AmberWarning.copy(alpha = 0.9f)
                        ConnectionStatus.FAILED -> RoseError.copy(alpha = 0.9f)
                        else -> MaterialTheme.colorScheme.outline
                    }

                    Box(
                        modifier = Modifier
                            .size(innerButtonSize)
                            .shadow(
                                elevation = if (connectionStatus == ConnectionStatus.CONNECTED) 16.dp else 4.dp,
                                shape = CircleShape,
                                spotColor = if (connectionStatus == ConnectionStatus.CONNECTED) EmeraldSuccess else Color.Black
                            )
                            .clip(CircleShape)
                            .background(buttonGradient)
                            .border(3.dp, buttonBorderColor, CircleShape)
                            .clickable {
                                viewModel.toggleConnection(
                                    onVpnPermissionRequired = {
                                        val prepareIntent = VpnService.prepare(context)
                                        if (prepareIntent != null) {
                                            vpnPermissionLauncher.launch(prepareIntent)
                                        }
                                    }
                                )
                            }
                            .testTag("power_toggle_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (connectionStatus == ConnectionStatus.CONNECTING ||
                            connectionStatus == ConnectionStatus.DISCONNECTING ||
                            connectionStatus == ConnectionStatus.SWITCHING ||
                            connectionStatus == ConnectionStatus.TESTING_CONNECTION
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(if (isSmallScreen) 40.dp else 48.dp),
                                strokeWidth = 3.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "دکمه اتصال و قطع وی‌پی‌ان",
                                tint = if (connectionStatus == ConnectionStatus.CONNECTED) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(powerIconSize)
                            )
                        }
                    }
                }

                // Connection Status Text
                val statusColor = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> EmeraldSuccess
                    ConnectionStatus.SWITCHING -> ElectricViolet
                    ConnectionStatus.TESTING_CONNECTION -> CyanPrimary
                    ConnectionStatus.CONNECTING -> AmberWarning
                    ConnectionStatus.FAILED, ConnectionStatus.DISCONNECTING -> RoseError
                    ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val statusText = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> "متصل • اینترنت آزاد و ایمن است 🔑"
                    ConnectionStatus.SWITCHING -> "در حال تعویض آنی سرور..."
                    ConnectionStatus.TESTING_CONNECTION -> "در حال تست کیفیت اتصال جدید..."
                    ConnectionStatus.CONNECTING -> "در حال تست و اتصال امن..."
                    ConnectionStatus.FAILED -> "خطا در اتصال به سرور"
                    ConnectionStatus.DISCONNECTING -> "در حال قطع اتصال..."
                    ConnectionStatus.DISCONNECTED -> "قطع شده (آماده اتصال)"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isSmallScreen) 13.sp else 14.sp
                        ),
                        color = statusColor
                    )
                }

                // Step Message
                AnimatedVisibility(
                    visible = connectingStepMessage.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = connectingStepMessage,
                        fontSize = 11.sp,
                        color = AmberWarning,
                        textAlign = TextAlign.Center
                    )
                }

                // Active Server Selector Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .then(
                            if (isServerSectionEnabled) {
                                Modifier.clickable { onNavigateToServers() }
                            } else {
                                Modifier
                            }
                        )
                        .testTag("selected_server_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = if (isSmallScreen) 10.dp else 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CyanPrimary.copy(alpha = 0.15f),
                                modifier = Modifier.size(if (isSmallScreen) 38.dp else 42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = selectedServer?.getCountryFlag() ?: "🌐",
                                        fontSize = if (isSmallScreen) 17.sp else 19.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "سرور متصل / فعال",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = selectedServer?.name ?: when (smartConnectMode) {
                                        SmartConnectMode.ALL_SUBS -> "هوشمند از تمام ساب‌ها (خودکار)"
                                        SmartConnectMode.CURRENT_SUB -> "هوشمند از ساب انتخابی (خودکار)"
                                        SmartConnectMode.MANUAL -> "هیچ سروری انتخاب نشده"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selectedServer != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${selectedServer!!.protocol} • ${selectedServer!!.transportType.uppercase()} • ${selectedServer!!.group}",
                                            fontSize = 10.sp,
                                            color = CyanPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (selectedServer!!.latencyMs > 0) {
                                            Text(
                                                text = " • ${selectedServer!!.latencyMs}ms",
                                                fontSize = 10.sp,
                                                color = EmeraldSuccess,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Change Server Action Button (Only visible if server section is enabled)
                        if (isServerSectionEnabled) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clickable { onNavigateToServers() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "تغییر",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanPrimary
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowForwardIos,
                                        contentDescription = null,
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Live Network Telemetry Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (isSmallScreen) 12.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "آمار ترافیک و سرعت زنده",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Download
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = CyanPrimary.copy(alpha = 0.15f),
                                    modifier = Modifier.size(if (isSmallScreen) 30.dp else 34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "دانلود",
                                        tint = CyanPrimary,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(text = "دانلود", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (connectionStatus == ConnectionStatus.CONNECTED) speedStats.formatDownloadSpeed() else "0 B/s",
                                        fontSize = if (isSmallScreen) 12.sp else 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Upload
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = ElectricViolet.copy(alpha = 0.15f),
                                    modifier = Modifier.size(if (isSmallScreen) 30.dp else 34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "آپلود",
                                        tint = ElectricViolet,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(text = "آپلود", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (connectionStatus == ConnectionStatus.CONNECTED) speedStats.formatUploadSpeed() else "0 B/s",
                                        fontSize = if (isSmallScreen) 12.sp else 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Duration
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = EmeraldSuccess.copy(alpha = 0.15f),
                                    modifier = Modifier.size(if (isSmallScreen) 30.dp else 34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "زمان",
                                        tint = EmeraldSuccess,
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(text = "مدت زمان", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (connectionStatus == ConnectionStatus.CONNECTED) speedStats.formatDuration() else "00:00",
                                        fontSize = if (isSmallScreen) 12.sp else 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Routing info bar
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSettingsSheet = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Route,
                                        contentDescription = null,
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "مسیریابی: ${routingMode.title}",
                                        fontSize = if (isSmallScreen) 10.sp else 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = "تنظیمات ⚙️",
                                    fontSize = 10.sp,
                                    color = CyanPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(
            currentSmartMode = smartConnectMode,
            onSmartModeChange = { viewModel.setSmartConnectMode(it) },
            currentRoutingMode = routingMode,
            onRoutingModeChange = { viewModel.setRoutingMode(it) },
            currentDns = dnsProvider,
            onDnsChange = { viewModel.setDnsProvider(it) },
            currentTestUrl = testUrl,
            onTestUrlChange = { viewModel.setTestUrl(it) },
            muxEnabled = muxEnabled,
            onToggleMux = { viewModel.toggleMux() },
            fragmentEnabled = fragmentEnabled,
            onToggleFragment = { viewModel.toggleFragment() },
            isLanSharingActive = isLanSharingActive,
            isLanHttpRunning = isLanHttpRunning,
            isLanSocksRunning = isLanSocksRunning,
            currentLanIp = currentLanIp,
            lanHttpEnabled = lanHttpEnabled,
            lanHttpPort = lanHttpPort,
            lanSocksEnabled = lanSocksEnabled,
            lanSocksPort = lanSocksPort,
            lanLastError = lanLastError,
            onToggleLanSharing = { viewModel.toggleLanSharing() },
            onSetLanHttpEnabled = { viewModel.setLanHttpEnabled(it) },
            onSetLanHttpPort = { viewModel.setLanHttpPort(it) },
            onSetLanSocksEnabled = { viewModel.setLanSocksEnabled(it) },
            onSetLanSocksPort = { viewModel.setLanSocksPort(it) },
            onRefreshLanIp = { viewModel.refreshLanIp() },
            onToggleServerSection = { viewModel.toggleServerSection() },
            isHideConfigSharingEnabled = isHideConfigSharingEnabled,
            onSetHideConfigSharing = { viewModel.setHideConfigSharingEnabled(it) },
            onAddSecretSubscription = { onDone ->
                viewModel.addAndFetchSubscription(
                    title = "سابسکریپشن مخفی",
                    url = "https://raw.githubusercontent.com/samadbfoxpro/mytestsub/refs/heads/main/config.txt",
                    onFinished = onDone
                )
            },
            onOpenDnsDiagnostics = {
                showSettingsSheet = false
                showDnsDiagnosticsSheet = true
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    // Smart DNS Diagnostics Bottom Sheet
    if (showDnsDiagnosticsSheet) {
        com.example.ui.components.dns.DnsDiagnosticsBottomSheet(
            diagnosticsState = dnsDiagnosticsState,
            benchmarkResult = dnsBenchmarkResult,
            isBenchmarking = isDnsBenchmarking,
            dnsMode = dnsMode,
            fakeDnsEnabled = fakeDnsEnabled,
            onSetDnsMode = { viewModel.setSmartDnsMode(it) },
            onSetFakeDnsEnabled = { viewModel.setFakeDnsEnabled(it) },
            onClearCache = { viewModel.clearSmartDnsCache() },
            onRunBenchmark = { viewModel.runDnsBenchmark() },
            onCancelBenchmark = { viewModel.cancelDnsBenchmark() },
            onDismiss = { showDnsDiagnosticsSheet = false }
        )
    }
}
