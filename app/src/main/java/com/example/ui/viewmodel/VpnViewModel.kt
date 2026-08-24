package com.example.ui.viewmodel

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.AppSettingsManager
import com.example.data.model.ConnectionStatus
import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import com.example.data.model.SmartConnectMode
import com.example.data.model.SpeedStats
import com.example.data.model.Subscription
import com.example.data.parser.ConfigParser
import com.example.data.ping.PingManager
import com.example.data.repository.ServerRepository
import com.example.service.XrayVpnService
import com.example.service.log.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val isError: Boolean = false)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ServerRepository

    init {
        val db = AppDatabase.getInstance(application)
        repository = ServerRepository(db.serverDao(), db.subscriptionDao())

        viewModelScope.launch(Dispatchers.IO) {
            val subs = db.subscriptionDao().getAllSubscriptionsList()
            if (subs.isEmpty()) {
                db.subscriptionDao().insertSubscription(
                    Subscription(id = 1L, title = "پیش‌فرض", url = "", lastUpdated = System.currentTimeMillis())
                )
            }

            // Clean up temporary "زنجیره تست" if it exists in the database
            val chainSub = db.subscriptionDao().getAllSubscriptionsList().find { it.title == "زنجیره تست" }
            if (chainSub != null) {
                val serversInChainSub = db.serverDao().getServersBySubscriptionList(chainSub.id)
                for (s in serversInChainSub) {
                    db.serverDao().deleteServer(s.id)
                }
                db.subscriptionDao().deleteSubscription(chainSub.id)
            }

            // Restore saved manual server selection if present
            val savedServerId = AppSettingsManager.getSelectedServerId(application)
            if (savedServerId > 0) {
                val server = db.serverDao().getServerById(savedServerId)
                if (server != null) {
                    db.serverDao().selectServer(savedServerId)
                }
            }
        }
    }

    // Subscriptions
    val subscriptions: StateFlow<List<Subscription>> = repository.allSubscriptions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _selectedSubscriptionId = MutableStateFlow(AppSettingsManager.getSelectedSubscriptionId(application))
    val selectedSubscriptionId: StateFlow<Long> = _selectedSubscriptionId.asStateFlow()

    // All raw servers
    val allServers: StateFlow<List<ServerConfig>> = repository.allServers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Current tab servers (Filtered by selected subscription)
    val currentSubServers: StateFlow<List<ServerConfig>> = combine(
        repository.allServers,
        _selectedSubscriptionId
    ) { servers, subId ->
        servers.filter { it.subscriptionId == subId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val selectedServer: StateFlow<ServerConfig?> = repository.selectedServer.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Connection mode (Persisted in SharedPreferences)
    private val _smartConnectMode = MutableStateFlow(AppSettingsManager.getSmartConnectMode(application))
    val smartConnectMode: StateFlow<SmartConnectMode> = _smartConnectMode.asStateFlow()

    // Connection state
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectingStepMessage = MutableStateFlow("")
    val connectingStepMessage: StateFlow<String> = _connectingStepMessage.asStateFlow()

    val speedStats: StateFlow<SpeedStats> = XrayVpnService.liveSpeedStats

    // Routing and settings (Persisted)
    private val _routingMode = MutableStateFlow(AppSettingsManager.getRoutingMode(application))
    val routingMode: StateFlow<RoutingMode> = _routingMode.asStateFlow()

    // Smart DNS States & Telemetry
    val dnsDiagnosticsState: StateFlow<com.example.service.dns.DnsDiagnosticsState> =
        com.example.service.XrayVpnService.globalSmartDnsEngine.diagnosticsManager.diagnosticsState

    private val _dnsBenchmarkResult = MutableStateFlow<com.example.service.dns.DnsPerformanceBenchmarkResult?>(null)
    val dnsBenchmarkResult: StateFlow<com.example.service.dns.DnsPerformanceBenchmarkResult?> = _dnsBenchmarkResult.asStateFlow()

    private val _isDnsBenchmarking = MutableStateFlow(false)
    val isDnsBenchmarking: StateFlow<Boolean> = _isDnsBenchmarking.asStateFlow()

    private val _dnsMode = MutableStateFlow(AppSettingsManager.getDnsMode(application))
    val dnsMode: StateFlow<String> = _dnsMode.asStateFlow()

    private val _fakeDnsEnabled = MutableStateFlow(AppSettingsManager.isFakeDnsEnabled(application))
    val fakeDnsEnabled: StateFlow<Boolean> = _fakeDnsEnabled.asStateFlow()

    private val _dnsProvider = MutableStateFlow(AppSettingsManager.getDnsProvider(application))
    val dnsProvider: StateFlow<String> = _dnsProvider.asStateFlow()

    private val _muxEnabled = MutableStateFlow(AppSettingsManager.isMuxEnabled(application))
    val muxEnabled: StateFlow<Boolean> = _muxEnabled.asStateFlow()

    private val _fragmentEnabled = MutableStateFlow(AppSettingsManager.isFragmentEnabled(application))
    val fragmentEnabled: StateFlow<Boolean> = _fragmentEnabled.asStateFlow()

    // Hidden Server Section Flag & Persistence
    private val _persistServerSection = MutableStateFlow(AppSettingsManager.isPersistServerSectionEnabled(application))
    val persistServerSection: StateFlow<Boolean> = _persistServerSection.asStateFlow()

    private val _isServerSectionEnabled = MutableStateFlow(true)
    val isServerSectionEnabled: StateFlow<Boolean> = _isServerSectionEnabled.asStateFlow()

    // Hide Config Sharing in Server List (Controlled via Secret Advanced Panel)
    private val _isHideConfigSharingEnabled = MutableStateFlow(AppSettingsManager.isHideConfigSharingEnabled(application))
    val isHideConfigSharingEnabled: StateFlow<Boolean> = _isHideConfigSharingEnabled.asStateFlow()

    fun setHideConfigSharingEnabled(enabled: Boolean) {
        _isHideConfigSharingEnabled.value = enabled
        AppSettingsManager.setHideConfigSharingEnabled(getApplication(), enabled)
        _uiMessage.value = UiMessage(if (enabled) "اشتراک‌گذاری کانفیگ‌ها مخفی و غیرفعال شد" else "اشتراک‌گذاری کانفیگ‌ها فعال شد")
    }

    // Ping / Real Delay Test URL
    private val _testUrl = MutableStateFlow(AppSettingsManager.getTestUrl(application))
    val testUrl: StateFlow<String> = _testUrl.asStateFlow()

    fun setTestUrl(url: String) {
        _testUrl.value = url
        AppSettingsManager.saveTestUrl(getApplication(), url)
        _uiMessage.value = UiMessage("آدرس تست پینگ تغییر یافت")
    }

    // Batch Ping state
    private val _isBatchTesting = MutableStateFlow(false)
    val isBatchTesting: StateFlow<Boolean> = _isBatchTesting.asStateFlow()

    private val _batchProgress = MutableStateFlow(0f)
    val batchProgress: StateFlow<Float> = _batchProgress.asStateFlow()

    private val _batchStatusText = MutableStateFlow("")
    val batchStatusText: StateFlow<String> = _batchStatusText.asStateFlow()

    // Snackbars / Feedback
    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    // LAN Sharing states & configuration
    val isLanSharingActive: StateFlow<Boolean> = com.example.service.proxy.LanSharingManager.isLanSharingActive
    val isLanHttpRunning: StateFlow<Boolean> = com.example.service.proxy.LanSharingManager.isHttpRunning
    val isLanSocksRunning: StateFlow<Boolean> = com.example.service.proxy.LanSharingManager.isSocksRunning
    val currentLanIp: StateFlow<String?> = com.example.service.proxy.LanSharingManager.currentLanIp
    val lanLastError: StateFlow<String?> = com.example.service.proxy.LanSharingManager.lastErrorMessage

    private val _lanHttpEnabled = MutableStateFlow(AppSettingsManager.isLanHttpEnabled(application))
    val lanHttpEnabled: StateFlow<Boolean> = _lanHttpEnabled.asStateFlow()

    private val _lanHttpPort = MutableStateFlow(AppSettingsManager.getLanHttpPort(application))
    val lanHttpPort: StateFlow<Int> = _lanHttpPort.asStateFlow()

    private val _lanSocksEnabled = MutableStateFlow(AppSettingsManager.isLanSocksEnabled(application))
    val lanSocksEnabled: StateFlow<Boolean> = _lanSocksEnabled.asStateFlow()

    private val _lanSocksPort = MutableStateFlow(AppSettingsManager.getLanSocksPort(application))
    val lanSocksPort: StateFlow<Int> = _lanSocksPort.asStateFlow()

    // Flight Log Recorder States & Actions
    val isLogRecording: StateFlow<Boolean> = LogRecorder.isRecording
    val recordedLogCount: StateFlow<Int> = LogRecorder.recordedCount
    val recordingDurationSec: StateFlow<Long> = LogRecorder.recordingDurationSec

    fun startLogRecording() {
        LogRecorder.startRecording(getApplication())
        _uiMessage.value = UiMessage("🔴 ضبط رویدادهای زنده وی‌پی‌ان آغاز شد")
    }

    fun stopLogRecording() {
        LogRecorder.stopRecording(getApplication())
        _uiMessage.value = UiMessage("⏹️ ضبط لاگ متوقف شد. آماده ذخیره و اشتراک‌گذاری")
    }

    fun saveLogToDownloads() {
        val result = LogRecorder.saveLogToDownloads(getApplication())
        result.onSuccess { path ->
            _uiMessage.value = UiMessage("✅ فایل لاگ در $path ذخیره شد")
        }.onFailure { err ->
            _uiMessage.value = UiMessage("❌ خطا در ذخیره لاگ: ${err.message}", isError = true)
        }
    }

    fun shareLogFile() {
        val success = LogRecorder.shareLogFile(getApplication())
        if (!success) {
            _uiMessage.value = UiMessage("❌ هیچ لاگی برای اشتراک‌گذاری وجود ندارد", isError = true)
        }
    }

    fun getLogSummaryText(): String = LogRecorder.getLogsAsString()

    private var connectionJob: Job? = null
    private var switchJob: Job? = null

    init {
        viewModelScope.launch {
            XrayVpnService.isVpnRunning.collect { isRunning ->
                if (!isRunning && _connectionStatus.value != ConnectionStatus.DISCONNECTED && _connectionStatus.value != ConnectionStatus.CONNECTING) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    _connectingStepMessage.value = ""
                }
            }
        }

        viewModelScope.launch {
            selectedServer.collect { server ->
                com.example.service.proxy.LanSharingManager.updateOutboundConfig(server)
            }
        }
    }

    fun toggleLanSharing() {
        if (isLanSharingActive.value) {
            com.example.service.proxy.LanSharingManager.stop()
            _uiMessage.value = UiMessage("اشتراک‌گذاری در شبکه محلی (LAN) متوقف شد 🛑")
        } else {
            startLanSharing()
        }
    }

    fun startLanSharing() {
        val success = com.example.service.proxy.LanSharingManager.start(
            context = getApplication(),
            enableHttp = _lanHttpEnabled.value,
            httpPort = _lanHttpPort.value,
            enableSocks = _lanSocksEnabled.value,
            socksPort = _lanSocksPort.value,
            activeServer = selectedServer.value
        )
        if (success) {
            val ip = com.example.service.proxy.LanSharingManager.currentLanIp.value ?: "IP محلی"
            _uiMessage.value = UiMessage("اشتراک‌گذاری روی $ip فعال شد 📡")
        } else {
            val err = com.example.service.proxy.LanSharingManager.lastErrorMessage.value ?: "خطا در راه‌اندازی LAN Sharing"
            _uiMessage.value = UiMessage("❌ $err", true)
        }
    }

    fun stopLanSharing() {
        com.example.service.proxy.LanSharingManager.stop()
    }

    fun setLanHttpEnabled(enabled: Boolean) {
        _lanHttpEnabled.value = enabled
        AppSettingsManager.setLanHttpEnabled(getApplication(), enabled)
        if (isLanSharingActive.value) {
            startLanSharing()
        }
    }

    fun setLanHttpPort(port: Int) {
        _lanHttpPort.value = port
        AppSettingsManager.setLanHttpPort(getApplication(), port)
        if (isLanSharingActive.value) {
            startLanSharing()
        }
    }

    fun setLanSocksEnabled(enabled: Boolean) {
        _lanSocksEnabled.value = enabled
        AppSettingsManager.setLanSocksEnabled(getApplication(), enabled)
        if (isLanSharingActive.value) {
            startLanSharing()
        }
    }

    fun setLanSocksPort(port: Int) {
        _lanSocksPort.value = port
        AppSettingsManager.setLanSocksPort(getApplication(), port)
        if (isLanSharingActive.value) {
            startLanSharing()
        }
    }

    fun refreshLanIp() {
        com.example.service.proxy.LanSharingManager.refreshLanIp(getApplication())
    }

    fun toggleServerSection() {
        val newVal = !_isServerSectionEnabled.value
        _isServerSectionEnabled.value = newVal
        if (_persistServerSection.value) {
            AppSettingsManager.setServerSectionPersisted(getApplication(), newVal)
        }
    }

    fun setPersistServerSection(enabled: Boolean) {
        _persistServerSection.value = enabled
        AppSettingsManager.setPersistServerSectionEnabled(getApplication(), enabled)
        if (enabled) {
            AppSettingsManager.setServerSectionPersisted(getApplication(), _isServerSectionEnabled.value)
        } else {
            AppSettingsManager.setServerSectionPersisted(getApplication(), false)
        }
    }

    fun selectSubscription(id: Long) {
        _selectedSubscriptionId.value = id
        AppSettingsManager.saveSelectedSubscriptionId(getApplication(), id)
    }

    fun setSmartConnectMode(mode: SmartConnectMode) {
        _smartConnectMode.value = mode
        AppSettingsManager.saveSmartConnectMode(getApplication(), mode)
        _uiMessage.value = UiMessage("حالت اتصال تنظیم شد: ${mode.title}")
    }

    fun setRoutingMode(mode: RoutingMode) {
        _routingMode.value = mode
        AppSettingsManager.saveRoutingMode(getApplication(), mode)
    }

    fun setSmartDnsMode(mode: String) {
        _dnsMode.value = mode
        AppSettingsManager.saveDnsMode(getApplication(), mode)
        com.example.service.XrayVpnService.globalSmartDnsEngine.setMode(mode, _fakeDnsEnabled.value)
    }

    fun setFakeDnsEnabled(enabled: Boolean) {
        _fakeDnsEnabled.value = enabled
        AppSettingsManager.saveFakeDnsEnabled(getApplication(), enabled)
        com.example.service.XrayVpnService.globalSmartDnsEngine.setMode(_dnsMode.value, enabled)
    }

    fun clearSmartDnsCache() {
        com.example.service.XrayVpnService.globalSmartDnsEngine.clearCache()
        _uiMessage.value = UiMessage("کش DNS با موفقیت پاکسازی شد 🧹")
    }

    private var dnsBenchmarkJob: kotlinx.coroutines.Job? = null

    fun runDnsBenchmark() {
        dnsBenchmarkJob?.cancel()
        dnsBenchmarkJob = viewModelScope.launch {
            _isDnsBenchmarking.value = true
            _uiMessage.value = UiMessage("در حال تست و بنچمارک زنده ریزالورهای DNS...")
            try {
                val engine = com.example.service.XrayVpnService.globalSmartDnsEngine
                val result = engine.diagnosticsManager.runPerformanceBenchmark(
                    resolvers = com.example.service.dns.SmartDnsEngine.AVAILABLE_RESOLVERS,
                    vpnService = com.example.service.XrayVpnService.instance,
                    cacheManager = engine.cacheManager
                )
                _dnsBenchmarkResult.value = result
                _uiMessage.value = UiMessage("تست کارایی و نشت DNS انجام شد ⚡")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiMessage.value = UiMessage("تست کارایی DNS متوقف شد 🛑")
            } catch (e: Exception) {
                _uiMessage.value = UiMessage("خطا در تست DNS: ${e.message}", true)
            } finally {
                _isDnsBenchmarking.value = false
            }
        }
    }

    fun cancelDnsBenchmark() {
        dnsBenchmarkJob?.cancel()
        dnsBenchmarkJob = null
        _isDnsBenchmarking.value = false
        _uiMessage.value = UiMessage("تست کارایی DNS لغو گردید 🛑")
    }

    fun setDnsProvider(dns: String) {
        _dnsProvider.value = dns
        AppSettingsManager.saveDnsProvider(getApplication(), dns)
    }

    fun toggleMux() {
        val newVal = !_muxEnabled.value
        _muxEnabled.value = newVal
        AppSettingsManager.saveMuxEnabled(getApplication(), newVal)
    }

    fun toggleFragment() {
        val newVal = !_fragmentEnabled.value
        _fragmentEnabled.value = newVal
        AppSettingsManager.saveFragmentEnabled(getApplication(), newVal)
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    fun selectServer(server: ServerConfig) {
        viewModelScope.launch {
            // Immediately mark as selected server in database, preferences, and state
            repository.selectServer(server.id)
            AppSettingsManager.saveSelectedServerId(getApplication(), server.id)

            val currentStatus = _connectionStatus.value
            val isVpnActive = XrayVpnService.isVpnRunning.value ||
                    currentStatus == ConnectionStatus.CONNECTED ||
                    currentStatus == ConnectionStatus.SWITCHING ||
                    currentStatus == ConnectionStatus.TESTING_CONNECTION

            if (isVpnActive) {
                // Cancel any pending switch job to eliminate race conditions from fast clicks
                switchJob?.cancel()
                switchJob = viewModelScope.launch {
                    try {
                        _connectionStatus.value = ConnectionStatus.SWITCHING
                        _connectingStepMessage.value = "در حال سوئیچ آنی به ${server.name}..."

                        // Perform hot switch on the VPN service
                        XrayVpnService.switchServer(getApplication(), server, _routingMode.value)

                        // Test the new node connectivity and quality automatically
                        _connectionStatus.value = ConnectionStatus.TESTING_CONNECTION
                        _connectingStepMessage.value = "در حال تست پینگ و پایداری نود جدید..."

                        val testResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            PingManager.testGoogleConnectivity(server)
                        }

                        // Save ping result for this server
                        repository.testServerLatency(server)

                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        if (testResult.isReachable) {
                            _connectingStepMessage.value = ""
                            _uiMessage.value = UiMessage("اتصال با موفقیت به ${server.name} منتقل شد (${testResult.latencyMs}ms) ⚡")
                        } else {
                            _connectingStepMessage.value = ""
                            _uiMessage.value = UiMessage("سوئیچ به ${server.name} انجام شد (پینگ بالا/ناموفق)", true)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Rapid server selection: let the next switchJob execute cleanly
                        throw e
                    } catch (e: Exception) {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _connectingStepMessage.value = ""
                        _uiMessage.value = UiMessage("خطا در تعویض کانفیگ: ${e.message}", true)
                    }
                }
            }
        }
    }

    fun toggleConnection(onVpnPermissionRequired: () -> Unit = {}) {
        val current = _connectionStatus.value

        if (current == ConnectionStatus.DISCONNECTED) {
            val prepareIntent = VpnService.prepare(getApplication())
            if (prepareIntent != null) {
                onVpnPermissionRequired()
                return
            }

            when (_smartConnectMode.value) {
                SmartConnectMode.ALL_SUBS -> startSmartConnect(fromAllSubs = true)
                SmartConnectMode.CURRENT_SUB -> startSmartConnect(fromAllSubs = false)
                SmartConnectMode.MANUAL -> startManualConnect()
            }
        } else {
            disconnect()
        }
    }

    fun onVpnPermissionApproved() {
        when (_smartConnectMode.value) {
            SmartConnectMode.ALL_SUBS -> startSmartConnect(fromAllSubs = true)
            SmartConnectMode.CURRENT_SUB -> startSmartConnect(fromAllSubs = false)
            SmartConnectMode.MANUAL -> startManualConnect()
        }
    }

    private fun startSmartConnect(fromAllSubs: Boolean) {
        connectionJob?.cancel()
        switchJob?.cancel()
        connectionJob = viewModelScope.launch {
            val candidatePool = if (fromAllSubs) {
                repository.getAllServersList()
            } else {
                repository.getServersBySubscriptionList(_selectedSubscriptionId.value)
            }

            if (candidatePool.isEmpty()) {
                val scopeName = if (fromAllSubs) "در تمام سابسکریپشن‌ها" else "در این سابسکریپشن"
                _uiMessage.value = UiMessage("هیچ سروری $scopeName برای اتصال وجود ندارد", true)
                return@launch
            }

            _connectionStatus.value = ConnectionStatus.CONNECTING

            val sortedCandidates = candidatePool.sortedWith(compareBy {
                when {
                    it.latencyMs > 0 -> it.latencyMs
                    it.latencyMs == -1L -> 500L
                    else -> 9999L
                }
            })

            var connectedNode: ServerConfig? = null

            for ((index, node) in sortedCandidates.withIndex()) {
                _connectingStepMessage.value = "در حال تست نود ${index + 1} از ${sortedCandidates.size}: ${node.name}..."
                delay(250)

                val testResult = PingManager.testGoogleConnectivity(node)

                if (testResult.isReachable) {
                    val updated = repository.testServerLatency(node)
                    repository.selectServer(node.id)
                    connectedNode = updated
                    _connectingStepMessage.value = "تایید شد! دسترسی به گوگل برقرار است (${testResult.latencyMs}ms)"
                    delay(300)
                    break
                } else {
                    _connectingStepMessage.value = "نود ${node.name} پاسخ نداد ⬅ بررسی نود بعدی..."
                    delay(350)
                }
            }

            if (connectedNode != null) {
                XrayVpnService.startVpn(getApplication(), connectedNode, _routingMode.value)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _connectingStepMessage.value = ""
                _uiMessage.value = UiMessage("با موفقیت به ${connectedNode.name} متصل شدید 🔑")
            } else {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _connectingStepMessage.value = ""
                _uiMessage.value = UiMessage("تمامی سرورها در تست اتصال ناموفق بودند.", true)
            }
        }
    }

    private fun startManualConnect() {
        val currentServer = selectedServer.value
        if (currentServer == null) {
            _uiMessage.value = UiMessage("لطفا ابتدا یک سرور از لیست انتخاب کنید", true)
            return
        }

        connectionJob?.cancel()
        switchJob?.cancel()
        connectionJob = viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _connectingStepMessage.value = "در حال بررسی و اتصال به ${currentServer.name}..."

            val testResult = PingManager.testGoogleConnectivity(currentServer)
            if (testResult.isReachable) {
                delay(300)
                XrayVpnService.startVpn(getApplication(), currentServer, _routingMode.value)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _connectingStepMessage.value = ""
                _uiMessage.value = UiMessage("متصل به ${currentServer.name} • تونل فعال شد 🔑")
            } else {
                _connectingStepMessage.value = ""
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _uiMessage.value = UiMessage("خطا در اتصال به ${currentServer.name}: سرور پاسخ نداد", true)
            }
        }
    }

    private fun disconnect() {
        connectionJob?.cancel()
        switchJob?.cancel()
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.DISCONNECTING
            _connectingStepMessage.value = ""
            XrayVpnService.stopVpn(getApplication())
            delay(350)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _uiMessage.value = UiMessage("اتصال وی‌پی‌ان قطع شد")
        }
    }

    fun testSingleServerLatency(server: ServerConfig) {
        viewModelScope.launch {
            _uiMessage.value = UiMessage("در حال تست پینگ ${server.name}...")
            val result = PingManager.testGoogleConnectivity(server, customTestUrl = _testUrl.value)
            if (result.isReachable) {
                val updated = repository.testServerLatency(server)
                _uiMessage.value = UiMessage("✅ پینگ: ${updated.latencyMs}ms (${result.message})")
            } else {
                _uiMessage.value = UiMessage("❌ سرور ${server.name} پاسخ نداد (تایم‌اوت)", true)
            }
        }
    }

    private var batchPingJob: kotlinx.coroutines.Job? = null

    // Ping ONLY the current subscription's servers
    fun batchPingCurrentSubscription() {
        val currentList = currentSubServers.value
        if (currentList.isEmpty()) {
            _uiMessage.value = UiMessage("هیچ سروری در این سابسکریپشن وجود ندارد", true)
            return
        }

        // Cancel previous job if running
        batchPingJob?.cancel()
        batchPingJob = viewModelScope.launch {
            try {
                _isBatchTesting.value = true
                _batchProgress.value = 0f
                _batchStatusText.value = "در حال تست پینگ 0/${currentList.size} سرور..."

                PingManager.batchTestLatency(
                    servers = currentList,
                    maxConcurrency = 8,
                    perServerTimeoutMs = 3000L,
                    customTestUrl = _testUrl.value
                ) { completed, total, updatedServer ->
                    repository.updateServer(updatedServer)
                    _batchProgress.value = completed.toFloat() / total.toFloat()
                    _batchStatusText.value = "در حال تست پینگ $completed از $total سرور این ساب..."
                }

                _isBatchTesting.value = false
                _batchProgress.value = 1f
                _uiMessage.value = UiMessage("تست پینگ ${currentList.size} سرور سابسکریپشن به پایان رسید ⚡")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _isBatchTesting.value = false
                _uiMessage.value = UiMessage("تست پینگ متوقف شد ⏹️")
            } catch (e: Exception) {
                _isBatchTesting.value = false
                _uiMessage.value = UiMessage("خطا در تست پینگ: ${e.message}", true)
            }
        }
    }

    fun cancelBatchPing() {
        batchPingJob?.cancel()
        batchPingJob = null
        _isBatchTesting.value = false
        _uiMessage.value = UiMessage("تست پینگ لغو شد ⏹️")
    }

    fun addSubscription(title: String, url: String) {
        if (title.isBlank()) {
            _uiMessage.value = UiMessage("لطفا عنوان سابسکریپشن را وارد کنید", true)
            return
        }

        viewModelScope.launch {
            _uiMessage.value = UiMessage("در حال افزودن سابسکریپشن '$title'...")
            val newSub = Subscription(
                title = title.trim(),
                url = url.trim(),
                lastUpdated = System.currentTimeMillis()
            )
            val newId = repository.insertSubscription(newSub)
            _selectedSubscriptionId.value = newId
            _uiMessage.value = UiMessage("سابسکریپشن '$title' با موفقیت ایجاد شد")
        }
    }

    fun addAndFetchSubscription(title: String, url: String, onFinished: ((Boolean, Int) -> Unit)? = null) {
        viewModelScope.launch {
            _uiMessage.value = UiMessage("در حال افزودن و دریافت سابسکریپشن '$title'...")
            val existing = repository.getAllSubscriptionsList().firstOrNull { it.url.trim() == url.trim() }
            val subToUpdate = if (existing != null) {
                existing
            } else {
                val newSub = Subscription(
                    title = title.trim(),
                    url = url.trim(),
                    lastUpdated = System.currentTimeMillis()
                )
                val newId = repository.insertSubscription(newSub)
                newSub.copy(id = newId)
            }
            _selectedSubscriptionId.value = subToUpdate.id

            val result = repository.updateSubscriptionFromUrl(subToUpdate)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _uiMessage.value = UiMessage("✅ سابسکریپشن '$title' با $count کانفیگ افزوده شد")
                onFinished?.invoke(true, count)
            } else {
                val err = result.exceptionOrNull()?.message ?: "خطا در دریافت اطلاعات سابسکریپشن"
                _uiMessage.value = UiMessage("❌ $err", true)
                onFinished?.invoke(false, 0)
            }
        }
    }

    fun updateSubscriptionFromUrl(sub: Subscription) {
        if (sub.url.isBlank()) {
            _uiMessage.value = UiMessage("این سابسکریپشن آدرس اینترنتی ندارد (دستی است)", true)
            return
        }

        viewModelScope.launch {
            _uiMessage.value = UiMessage("در حال دریافت و بروزرسانی سابسکریپشن '${sub.title}'...")
            val result = repository.updateSubscriptionFromUrl(sub)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _uiMessage.value = UiMessage("✅ سابسکریپشن '${sub.title}' بروز شد ($count سرور دریافت گردید)")
            } else {
                _uiMessage.value = UiMessage("❌ خطا در بروزرسانی: ${result.exceptionOrNull()?.message}", true)
            }
        }
    }

    fun deleteSubscription(sub: Subscription) {
        if (sub.id == 1L) {
            _uiMessage.value = UiMessage("سابسکریپشن پیش‌فرض قابل حذف نیست", true)
            return
        }

        viewModelScope.launch {
            repository.deleteSubscription(sub.id)
            _selectedSubscriptionId.value = 1L
            _uiMessage.value = UiMessage("سابسکریپشن '${sub.title}' و کانفیگ‌های آن حذف شدند")
        }
    }

    fun importToCurrentSubscription(clipText: String) {
        val text = clipText.trim()
        if (text.isBlank()) {
            _uiMessage.value = UiMessage("محتوای ورودی یا کیوآرکد خالی است", true)
            return
        }

        viewModelScope.launch {
            val curSub = subscriptions.value.find { it.id == _selectedSubscriptionId.value }
            val groupTitle = curSub?.title ?: "پیش‌فرض"

            if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
                _uiMessage.value = UiMessage("در حال دریافت کانفیگ‌ها از لینک...")
                val fetchResult = PingManager.fetchSubscription(text)
                if (fetchResult.isSuccess) {
                    val content = fetchResult.getOrNull() ?: ""
                    val count = repository.importToSubscription(content, _selectedSubscriptionId.value, groupTitle)
                    if (count > 0) {
                        _uiMessage.value = UiMessage("$count کانفیگ با موفقیت به سابسکریپشن '$groupTitle' اضافه شد ⚡")
                    } else {
                        _uiMessage.value = UiMessage("هیچ کانفیگی در این لینک یافت نشد", true)
                    }
                } else {
                    _uiMessage.value = UiMessage("خطا در دریافت از لینک سابسکریپشن", true)
                }
            } else {
                val count = repository.importToSubscription(text, _selectedSubscriptionId.value, groupTitle)
                if (count > 0) {
                    _uiMessage.value = UiMessage(if (count == 1) "کانفیگ با موفقیت به سابسکریپشن '$groupTitle' اضافه شد ⚡" else "$count کانفیگ به سابسکریپشن '$groupTitle' اضافه شد ⚡")
                } else {
                    _uiMessage.value = UiMessage("فرمت کیوآرکد یا کانفیگ نامعتبر است", true)
                }
            }
        }
    }

    fun saveServer(server: ServerConfig) {
        viewModelScope.launch {
            val curSub = subscriptions.value.find { it.id == _selectedSubscriptionId.value }
            val configWithSub = server.copy(
                subscriptionId = _selectedSubscriptionId.value,
                group = curSub?.title ?: "پیش‌فرض"
            )
            if (server.id == 0L) {
                repository.insertServer(configWithSub)
                _uiMessage.value = UiMessage("کانفیگ ${server.name} افزوده شد")
            } else {
                repository.updateServer(server)
                _uiMessage.value = UiMessage("کانفیگ ${server.name} ویرایش شد")
            }
        }
    }

    fun moveServerToSubscription(server: ServerConfig, targetSubId: Long) {
        viewModelScope.launch {
            val targetSub = subscriptions.value.find { it.id == targetSubId }
            val targetTitle = targetSub?.title ?: "پیش‌فرض"
            val updated = server.copy(
                subscriptionId = targetSubId,
                group = targetTitle
            )
            repository.updateServer(updated)
            _uiMessage.value = UiMessage("کانفیگ '${server.name}' به سابسکریپشن '$targetTitle' منتقل شد 📦")
        }
    }

    fun deleteServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.deleteServer(server.id)
            _uiMessage.value = UiMessage("کانفیگ ${server.name} حذف شد")
        }
    }

    fun deleteTimeoutServersInCurrentSub() {
        viewModelScope.launch {
            repository.deleteTimeoutServersInSubscription(_selectedSubscriptionId.value)
            _uiMessage.value = UiMessage("کانفیگ‌های تایم‌اوت این سابسکریپشن پاکسازی شدند")
        }
    }

    fun showToast(message: String, isError: Boolean = false) {
        _uiMessage.value = UiMessage(message, isError)
    }

    override fun onCleared() {
        super.onCleared()
        connectionJob?.cancel()
    }
}
