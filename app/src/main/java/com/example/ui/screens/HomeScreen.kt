package com.example.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ConnectionStatus
import com.example.data.model.ServerConfig
import com.example.ui.components.AddConfigDialog
import com.example.ui.components.FilterSearchHeader
import com.example.ui.components.HeroConnectionCard
import com.example.ui.components.ServerCard
import com.example.ui.components.ServerDetailBottomSheet
import com.example.ui.components.SettingsBottomSheet
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val servers by viewModel.filteredServers.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val speedStats by viewModel.speedStats.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()
    val dnsProvider by viewModel.dnsProvider.collectAsStateWithLifecycle()
    val muxEnabled by viewModel.muxEnabled.collectAsStateWithLifecycle()
    val fragmentEnabled by viewModel.fragmentEnabled.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedProtocol by viewModel.selectedProtocolFilter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()

    val isBatchTesting by viewModel.isBatchTesting.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val batchStatusText by viewModel.batchStatusText.collectAsStateWithLifecycle()

    val isSmartMode by viewModel.isSmartMode.collectAsStateWithLifecycle()
    val connectingStepMessage by viewModel.connectingStepMessage.collectAsStateWithLifecycle()

    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionApproved()
        }
    }

    // Dialog & Sheet States
    var showAddDialog by remember { mutableStateOf(false) }
    var initialClipboardForDialog by remember { mutableStateOf("") }
    var detailServer by remember { mutableStateOf<ServerConfig?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var topMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiMessage) {
        uiMessage?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.clearUiMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                                    .size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "v2rayNG",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ElectricViolet.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "PRO",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        color = ElectricViolet,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "مسیریاب امن هسته Xray",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Smart Auto-Select Fastest Button
                    IconButton(
                        onClick = { viewModel.autoSelectFastest() },
                        modifier = Modifier.testTag("auto_select_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "انتخاب هوشمند سریع‌ترین نود",
                            tint = NeonCyan
                        )
                    }

                    // Batch Ping Latency Test
                    IconButton(
                        onClick = { viewModel.batchPingAll() },
                        modifier = Modifier.testTag("batch_ping_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkPing,
                            contentDescription = "تست پینگ همگانی",
                            tint = CyanPrimary
                        )
                    }

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

                    // Top Overflow Menu
                    Box {
                        IconButton(onClick = { topMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "منو",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = topMenuExpanded,
                            onDismissRequest = { topMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("افزودن کانفیگ از کلیپ‌بورد") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = CyanPrimary) },
                                onClick = {
                                    topMenuExpanded = false
                                    val clip = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    viewModel.importFromClipboard(clip)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("انتخاب خودکار کمترین پینگ") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonCyan) },
                                onClick = {
                                    topMenuExpanded = false
                                    viewModel.autoSelectFastest()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف سرورهای قطع شده") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = RoseError) },
                                onClick = {
                                    topMenuExpanded = false
                                    viewModel.deleteTimeoutServers()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("پاکسازی تمام سرورها", color = RoseError) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = RoseError) },
                                onClick = {
                                    topMenuExpanded = false
                                    viewModel.clearAllServers()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val clip = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    initialClipboardForDialog = clip
                    showAddDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("افزودن کانفیگ", fontWeight = FontWeight.Bold) },
                containerColor = CyanPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("add_config_fab")
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Batch Test Progress Bar
            AnimatedVisibility(
                visible = isBatchTesting,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = batchStatusText,
                            fontSize = 11.sp,
                            color = CyanPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(batchProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = CyanPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { batchProgress },
                        color = CyanPrimary,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Hero Connection Panel
                item {
                    HeroConnectionCard(
                        connectionStatus = connectionStatus,
                        selectedServer = selectedServer,
                        speedStats = speedStats,
                        routingMode = routingMode,
                        isSmartMode = isSmartMode,
                        connectingStepMessage = connectingStepMessage,
                        onToggleConnection = {
                            viewModel.toggleConnection(
                                onVpnPermissionRequired = {
                                    val prepareIntent = VpnService.prepare(context)
                                    if (prepareIntent != null) {
                                        vpnPermissionLauncher.launch(prepareIntent)
                                    }
                                }
                            )
                        },
                        onToggleSmartMode = { viewModel.toggleSmartMode() },
                        onOpenRoutingSettings = { showSettingsSheet = true }
                    )
                }

                // Filter & Search Controls
                item {
                    FilterSearchHeader(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        selectedProtocol = selectedProtocol,
                        onProtocolSelect = { viewModel.setProtocolFilter(it) },
                        sortOrder = sortOrder,
                        onSortOrderChange = { viewModel.setSortOrder(it) }
                    )
                }

                // Server Section Header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "لیست سرورها و نودها",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "${servers.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // One-tap smart fastest select
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NeonCyan.copy(alpha = 0.12f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.autoSelectFastest() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "کمترین پینگ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                            }
                        }
                    }
                }

                // Empty State
                if (servers.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = CardDefaults.outlinedCardBorder(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "هیچ کانفیگی یافت نشد",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "دکمه '+ افزودن کانفیگ' را بزنید تا لینک‌های VLESS، VMess، Trojan یا سابسکریپشن را وارد کنید",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Server Cards List
                items(
                    items = servers,
                    key = { it.id }
                ) { server ->
                    ServerCard(
                        server = server,
                        isSelected = selectedServer?.id == server.id,
                        onSelect = { viewModel.selectServer(server) },
                        onTestPing = { viewModel.testSingleServerLatency(server) },
                        onEdit = {
                            detailServer = server
                        },
                        onCopyUri = {
                            val clip = ClipData.newPlainText("Xray Config", server.rawUri.ifBlank { "vless://${server.uuid}@${server.address}:${server.port}" })
                            clipboardManager.setPrimaryClip(clip)
                        },
                        onShowDetail = {
                            detailServer = server
                        },
                        onDelete = { viewModel.deleteServer(server) }
                    )
                }

                // Extra Bottom Spacer for FAB
                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    // Add Config Dialog
    if (showAddDialog) {
        AddConfigDialog(
            initialClipboardText = initialClipboardForDialog,
            onDismiss = { showAddDialog = false },
            onImportText = { viewModel.importFromClipboard(it) },
            onImportSubscription = { viewModel.importFromSubscriptionUrl(it) },
            onSaveManualServer = { viewModel.saveServer(it) }
        )
    }

    // Detail Bottom Sheet
    detailServer?.let { server ->
        ServerDetailBottomSheet(
            server = server,
            onDismiss = { detailServer = null },
            onCopyLink = { uri ->
                val clip = ClipData.newPlainText("Xray Link", uri)
                clipboardManager.setPrimaryClip(clip)
            },
            onCopyJson = { json ->
                val clip = ClipData.newPlainText("Xray JSON Config", json)
                clipboardManager.setPrimaryClip(clip)
            },
            onTestPing = { viewModel.testSingleServerLatency(server) },
            onDelete = {
                viewModel.deleteServer(server)
                detailServer = null
            }
        )
    }

    // Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsBottomSheet(
            currentRoutingMode = routingMode,
            onRoutingModeChange = { viewModel.setRoutingMode(it) },
            currentDns = dnsProvider,
            onDnsChange = { viewModel.setDnsProvider(it) },
            muxEnabled = muxEnabled,
            onToggleMux = { viewModel.toggleMux() },
            fragmentEnabled = fragmentEnabled,
            onToggleFragment = { viewModel.toggleFragment() },
            onDismiss = { showSettingsSheet = false }
        )
    }
}
