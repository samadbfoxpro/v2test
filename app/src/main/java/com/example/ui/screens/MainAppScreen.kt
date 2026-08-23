package com.example.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.viewmodel.VpnViewModel

@Composable
fun MainAppScreen(
    viewModel: VpnViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val isServerSectionEnabled by viewModel.isServerSectionEnabled.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    LaunchedEffect(isServerSectionEnabled) {
        if (!isServerSectionEnabled && selectedTab != 0) {
            selectedTab = 0
        }
    }

    LaunchedEffect(uiMessage) {
        uiMessage?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.clearUiMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isServerSectionEnabled) {
                // Floating Glassmorphic Navigation Dock with dynamic system insets (auto-adapts to 3-button & gesture navigation)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 14.dp,
                                shape = RoundedCornerShape(26.dp),
                                ambientColor = CyanPrimary.copy(alpha = 0.2f),
                                spotColor = CyanPrimary.copy(alpha = 0.35f)
                            ),
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        border = BorderStroke(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    CyanPrimary.copy(alpha = 0.4f),
                                    ElectricViolet.copy(alpha = 0.25f),
                                    NeonCyan.copy(alpha = 0.4f)
                                )
                            )
                        )
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tab 0: Connection
                        GlassNavButton(
                            title = "اتصال و وضعیت",
                            icon = if (selectedTab == 0) Icons.Filled.PowerSettingsNew else Icons.Outlined.PowerSettingsNew,
                            isSelected = selectedTab == 0,
                            activeColor = CyanPrimary,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedTab = 0 }
                        )

                        // Tab 1: Servers & Configs
                        GlassNavButton(
                            title = "سرورها و کانفیگ",
                            icon = if (selectedTab == 1) Icons.Filled.Dns else Icons.Outlined.Dns,
                            isSelected = selectedTab == 1,
                            activeColor = NeonCyan,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedTab = 1 }
                        )
                    }
                }
            }
        }
    }
    ) { innerPadding ->
        Crossfade(
            targetState = if (isServerSectionEnabled) selectedTab else 0,
            label = "tab_crossfade",
            modifier = Modifier.padding(innerPadding)
        ) { tabIndex ->
            when (tabIndex) {
                0 -> HomeScreen(
                    viewModel = viewModel,
                    onNavigateToServers = {
                        if (isServerSectionEnabled) {
                            selectedTab = 1
                        }
                    }
                )
                1 -> if (isServerSectionEnabled) {
                    ServersScreen(
                        viewModel = viewModel
                    )
                } else {
                    HomeScreen(
                        viewModel = viewModel,
                        onNavigateToServers = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 0.98f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "nav_scale"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 200),
        label = "nav_color"
    )

    val bgModifier = if (isSelected) {
        Modifier
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        activeColor.copy(alpha = 0.18f),
                        activeColor.copy(alpha = 0.08f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        activeColor.copy(alpha = 0.5f),
                        activeColor.copy(alpha = 0.2f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .then(bgModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 9.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(if (isSelected) 20.dp else 18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = if (isSelected) 12.sp else 11.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor
            )
        }
    }
}
