package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ServerConfig
import com.example.data.model.Subscription
import com.example.ui.components.AddConfigDialog
import com.example.ui.components.AddSubscriptionDialog
import com.example.ui.components.CreateProxyChainDialog
import com.example.ui.components.MoveSubscriptionDialog
import com.example.ui.components.QrCodeShareDialog
import com.example.ui.components.QrScannerDialog
import com.example.ui.components.ServerCard
import com.example.ui.components.ServerDetailBottomSheet
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RoseError
import com.example.ui.viewmodel.VpnViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val selectedSubId by viewModel.selectedSubscriptionId.collectAsStateWithLifecycle()
    val serversInSub by viewModel.currentSubServers.collectAsStateWithLifecycle()
    val allServers by viewModel.allServers.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()

    val isBatchTesting by viewModel.isBatchTesting.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val batchStatusText by viewModel.batchStatusText.collectAsStateWithLifecycle()
    val isHideConfigSharingEnabled by viewModel.isHideConfigSharingEnabled.collectAsStateWithLifecycle()

    val activeSubscription = subscriptions.find { it.id == selectedSubId } ?: subscriptions.firstOrNull()

    // Dialog & Sheet States
    var showAddConfigDialog by remember { mutableStateOf(false) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var showCreateProxyChainDialog by remember { mutableStateOf(false) }
    var editingChainServer by remember { mutableStateOf<ServerConfig?>(null) }
    var movingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var initialClipboardForDialog by remember { mutableStateOf("") }
    var detailServer by remember { mutableStateOf<ServerConfig?>(null) }
    var qrCodeShareServer by remember { mutableStateOf<ServerConfig?>(null) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var sortByPing by remember { mutableStateOf(false) }

    // Sorted server list based on latency (lowest / best ping from top to bottom)
    val displayedServers = remember(serversInSub, sortByPing) {
        if (sortByPing) {
            serversInSub.sortedWith(compareBy(
                { when {
                    it.latencyMs > 0 -> 0      // Working servers first (best ping)
                    it.latencyMs == -1L -> 1   // Untested servers in middle
                    else -> 2                  // Timeout / failed servers at bottom
                }},
                { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
            ))
        } else {
            serversInSub
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "مدیریت سرورها",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "${serversInSub.size} سرور • ${subscriptions.size} سابسکریپشن",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    // QR Code Scanner Action Button
                    IconButton(
                        onClick = { showQrScannerDialog = true },
                        modifier = Modifier.size(34.dp).testTag("qr_scan_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "اسکن کد QR با دوربین",
                            tint = CyanPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Test Latency ONLY for this subscription (or cancel if running)
                    IconButton(
                        onClick = {
                            if (isBatchTesting) {
                                viewModel.cancelBatchPing()
                            } else {
                                viewModel.batchPingCurrentSubscription()
                            }
                        },
                        modifier = Modifier.size(34.dp).testTag("batch_ping_sub_button")
                    ) {
                        Icon(
                            imageVector = if (isBatchTesting) Icons.Default.Close else Icons.Default.NetworkPing,
                            contentDescription = if (isBatchTesting) "توقف تست پینگ" else "تست پینگ سرورها",
                            tint = if (isBatchTesting) RoseError else CyanPrimary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Sort by Best Ping Button (Ascending: Low ms -> High ms)
                    IconButton(
                        onClick = {
                            sortByPing = !sortByPing
                            viewModel.showToast(
                                if (sortByPing) "مرتب‌سازی بر اساس بهترین پینگ فعال شد"
                                else "مرتب‌سازی پیش‌فرض"
                            )
                        },
                        modifier = Modifier.size(34.dp).testTag("sort_by_ping_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "مرتب‌سازی بر اساس بهترین پینگ",
                            tint = if (sortByPing) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Overflow Menu
                    Box {
                        IconButton(
                            onClick = { topMenuExpanded = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "منوی بیشتر",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = topMenuExpanded,
                            onDismissRequest = { topMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("افزودن سابسکریپشن جدید") },
                                leadingIcon = { Icon(Icons.Default.AddLink, contentDescription = null, tint = NeonCyan) },
                                onClick = {
                                    topMenuExpanded = false
                                    showAddSubDialog = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("🔗 ساخت زنجیره پروکسی (Proxy Chain)") },
                                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = ElectricViolet) },
                                onClick = {
                                    topMenuExpanded = false
                                    showCreateProxyChainDialog = true
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("افزودن کانفیگ دستی به این ساب") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = CyanPrimary) },
                                onClick = {
                                    topMenuExpanded = false
                                    val clip = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    initialClipboardForDialog = clip
                                    showAddConfigDialog = true
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (sortByPing) "مرتب‌سازی: پیش‌فرض"
                                        else "مرتب‌سازی بر اساس بهترین پینگ"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = null,
                                        tint = if (sortByPing) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    topMenuExpanded = false
                                    sortByPing = !sortByPing
                                    viewModel.showToast(
                                        if (sortByPing) "مرتب‌سازی بر اساس بهترین پینگ فعال شد"
                                        else "مرتب‌سازی پیش‌فرض"
                                    )
                                }
                            )

                            if (activeSubscription?.isRemote == true) {
                                DropdownMenuItem(
                                    text = { Text("بروزرسانی آنلاین این سابسکریپشن") },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null, tint = CyanPrimary) },
                                    onClick = {
                                        topMenuExpanded = false
                                        viewModel.updateSubscriptionFromUrl(activeSubscription)
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text("حذف سرورهای قطع شده این ساب") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = RoseError) },
                                onClick = {
                                    topMenuExpanded = false
                                    viewModel.deleteTimeoutServersInCurrentSub()
                                }
                            )

                            if (activeSubscription != null && activeSubscription.id != 1L) {
                                DropdownMenuItem(
                                    text = { Text("حذف کل این سابسکریپشن", color = RoseError) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = RoseError) },
                                    onClick = {
                                        topMenuExpanded = false
                                        viewModel.deleteSubscription(activeSubscription)
                                    }
                                )
                            }
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
                    showAddConfigDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("افزودن کانفیگ", fontWeight = FontWeight.Bold) },
                containerColor = CyanPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("add_config_fab")
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Subscription Tabs (Like v2rayNG / Outline)
            if (subscriptions.isNotEmpty()) {
                val selectedIndex = subscriptions.indexOfFirst { it.id == selectedSubId }.coerceAtLeast(0)

                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = CyanPrimary,
                    edgePadding = 16.dp,
                    indicator = { tabPositions ->
                        if (selectedIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = CyanPrimary,
                                height = 3.dp
                            )
                        }
                    }
                ) {
                    subscriptions.forEachIndexed { index, sub ->
                        val isSelected = sub.id == selectedSubId
                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.selectSubscription(sub.id) },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = sub.title,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (sub.isRemote) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "🔗",
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }

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
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${(batchProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = CyanPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = RoseError.copy(alpha = 0.15f),
                                modifier = Modifier.clickable { viewModel.cancelBatchPing() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "لغو",
                                        tint = RoseError,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "لغو",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoseError
                                    )
                                }
                            }
                        }
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Subscription Info Banner & Quick Action
                activeSubscription?.let { sub ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = sub.title,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = CyanPrimary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    text = "${serversInSub.size} سرور",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CyanPrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (sub.isRemote) {
                                            Text(
                                                text = sub.url,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (sub.lastUpdated > 0) {
                                                val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(sub.lastUpdated))
                                                Text(
                                                    text = "آخرین بروزرسانی: $dateStr",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "سابسکریپشن دستی • کانفیگ‌ها را با دکمه افزودن اضافه کنید",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Action button inside banner
                                    if (sub.isRemote) {
                                        Button(
                                            onClick = { viewModel.updateSubscriptionFromUrl(sub) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Sync,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(text = "بروزرسانی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                val clip = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                                initialClipboardForDialog = clip
                                                showAddConfigDialog = true
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = CyanPrimary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(text = "افزودن کانفیگ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                                        }
                                    }
                                }

                                // Quick Ping button for this sub & Proxy Chain shortcut
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { viewModel.batchPingCurrentSubscription() }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.NetworkPing,
                                                    contentDescription = null,
                                                    tint = CyanPrimary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "تست پینگ این ساب",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CyanPrimary
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = ElectricViolet.copy(alpha = 0.12f),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { showCreateProxyChainDialog = true }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Link,
                                                    contentDescription = null,
                                                    tint = ElectricViolet,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "+ زنجیره پروکسی",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ElectricViolet
                                                )
                                            }
                                        }
                                    }

                                    if (serversInSub.any { it.latencyMs == -2L }) {
                                        Text(
                                            text = "پاکسازی 🧹",
                                            fontSize = 11.sp,
                                            color = RoseError,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { viewModel.deleteTimeoutServersInCurrentSub() }
                                                .padding(4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Empty state for current subscription
                if (serversInSub.isEmpty()) {
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
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "هیچ کانفیگی در این سابسکریپشن وجود ندارد",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (activeSubscription?.isRemote == true)
                                        "دکمه 'بروزرسانی' بالا را بزنید تا کانفیگ‌ها از آدرس دریافت شوند"
                                    else
                                        "با زدن دکمه 'افزودن کانفیگ' یا '📷 اسکن QR'، کانفیگ‌ها را اضافه کنید",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Server Cards in current subscription
                items(
                    items = displayedServers,
                    key = { it.id }
                ) { server ->
                    ServerCard(
                        server = server,
                        isSelected = selectedServer?.id == server.id,
                        onSelect = { viewModel.selectServer(server) },
                        onTestPing = { viewModel.testSingleServerLatency(server) },
                        onEdit = {
                            if (server.isProxyChain || server.protocol.equals("CHAIN", ignoreCase = true)) {
                                editingChainServer = server
                                showCreateProxyChainDialog = true
                            } else {
                                initialClipboardForDialog = server.rawUri
                                showAddConfigDialog = true
                            }
                        },
                        onCopyUri = {
                            val uri = com.example.data.parser.ConfigParser.exportToUri(server)
                            val clip = ClipData.newPlainText("Xray Config", uri)
                            clipboardManager.setPrimaryClip(clip)
                            viewModel.showToast("لینک کانفیگ کپی شد 📋")
                        },
                        onShowDetail = { detailServer = server },
                        onShowQrCode = { qrCodeShareServer = server },
                        isHideConfigSharingEnabled = isHideConfigSharingEnabled,
                        onMoveToSub = { movingServer = server },
                        onDelete = { viewModel.deleteServer(server) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Live QR Scanner Dialog
    if (showQrScannerDialog) {
        QrScannerDialog(
            onDismiss = { showQrScannerDialog = false },
            onScanned = { qrContent ->
                showQrScannerDialog = false
                viewModel.importToCurrentSubscription(qrContent)
            }
        )
    }

    // Create / Edit Proxy Chain Dialog
    if (showCreateProxyChainDialog) {
        CreateProxyChainDialog(
            availableServers = allServers,
            subscriptions = subscriptions,
            editingChain = editingChainServer,
            onDismiss = { 
                showCreateProxyChainDialog = false
                editingChainServer = null
            },
            onSaveChain = { chainServer ->
                viewModel.saveServer(chainServer)
                editingChainServer = null
            }
        )
    }

    // Add Subscription Dialog
    if (showAddSubDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddSubDialog = false },
            onSave = { title, url ->
                viewModel.addSubscription(title, url)
            }
        )
    }

    // Add Config Dialog
    if (showAddConfigDialog) {
        AddConfigDialog(
            initialClipboardText = initialClipboardForDialog,
            onDismiss = { showAddConfigDialog = false },
            onImportText = { viewModel.importToCurrentSubscription(it) },
            onImportSubscription = { viewModel.addSubscription("سابسکریپشن جدید", it) },
            onSaveManualServer = { viewModel.saveServer(it) }
        )
    }

    // Move to Subscription Bottom Sheet
    movingServer?.let { serverToMove ->
        MoveSubscriptionDialog(
            server = serverToMove,
            subscriptions = subscriptions,
            allServers = allServers,
            onDismiss = { movingServer = null },
            onMoveToSubscription = { targetSubId ->
                viewModel.moveServerToSubscription(serverToMove, targetSubId)
            }
        )
    }

    // Server Detail Sheet
    detailServer?.let { server ->
        ServerDetailBottomSheet(
            server = server,
            onDismiss = { detailServer = null },
            onCopyLink = { uri ->
                val clip = ClipData.newPlainText("Xray Link", uri)
                clipboardManager.setPrimaryClip(clip)
                viewModel.showToast("لینک کانفیگ کپی شد 📋")
            },
            onCopyJson = { json ->
                val clip = ClipData.newPlainText("Xray JSON Config", json)
                clipboardManager.setPrimaryClip(clip)
                viewModel.showToast("پیکربندی JSON کپی شد 📋")
            },
            onTestPing = { viewModel.testSingleServerLatency(server) },
            onShowQrCode = {
                detailServer = null
                qrCodeShareServer = server
            },
            isHideConfigSharingEnabled = isHideConfigSharingEnabled,
            onEdit = {
                detailServer = null
                if (server.isProxyChain || server.protocol.equals("CHAIN", ignoreCase = true)) {
                    editingChainServer = server
                    showCreateProxyChainDialog = true
                } else {
                    initialClipboardForDialog = server.rawUri
                    showAddConfigDialog = true
                }
            },
            onMoveToSub = {
                detailServer = null
                movingServer = server
            },
            onDelete = {
                viewModel.deleteServer(server)
                detailServer = null
            }
        )
    }

    // QR Code Share Dialog
    qrCodeShareServer?.let { server ->
        QrCodeShareDialog(
            server = server,
            onDismiss = { qrCodeShareServer = null }
        )
    }
}
