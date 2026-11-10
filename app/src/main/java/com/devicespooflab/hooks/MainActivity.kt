package com.devicespooflab.hooks

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TrueDarkScheme = darkColorScheme(
    primary = Color(0xFF5E9BFF),
    onPrimary = Color(0xFF001D3D),
    primaryContainer = Color(0xFF1A2C4E),
    secondary = Color(0xFF34D399),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF151A24),
    surfaceVariant = Color(0xFF1E2533),
    onBackground = Color(0xFFEFF3F8),
    onSurface = Color(0xFFEFF3F8)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = TrueDarkScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var appList by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var statusBanner by remember { mutableStateOf("Ready • Select a scoped app to spoof & launch") }

    var wipeDataOnReset by remember { mutableStateOf(prefs.getBoolean("wipe_data", true)) }
    var autoGrantPerms by remember { mutableStateOf(prefs.getBoolean("auto_grant", true)) }
    var rotatePresetOnReset by remember { mutableStateOf(prefs.getBoolean("rotate_preset", true)) }
    var perAppIsolation by remember { mutableStateOf(prefs.getBoolean("per_app", true)) }
    var selectedPresetIdx by remember { mutableIntStateOf(prefs.getInt("preset_idx", 0)) }
    var pinnedPackages by remember {
        mutableStateOf(prefs.getStringSet("pinned_pkgs", emptySet())?.toSet() ?: emptySet())
    }

    var inspectedPkg by remember { mutableStateOf<String?>(null) }

    fun refreshApps() {
        scope.launch {
            isLoadingApps = true
            val (loaded, scopedSet) = SpoofEngine.loadInstalledApps(context, pinnedPackages)
            appList = loaded
            isLoadingApps = false
            statusBanner = "Detected ${scopedSet.size} apps in LSPosed scope"
        }
    }

    LaunchedEffect(Unit) {
        refreshApps()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DeviceSpoofLab • FakerFlow",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = statusBanner,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { refreshApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Scope")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    label = { Text("Scoped Apps") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    label = { Text("Identity & Hooks") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                AppsWorkflowTab(
                    appList = appList,
                    isLoading = isLoadingApps,
                    wipeData = wipeDataOnReset,
                    autoGrant = autoGrantPerms,
                    rotatePreset = rotatePresetOnReset,
                    perAppIsolation = perAppIsolation,
                    selectedPresetIdx = selectedPresetIdx,
                    onToggleWipe = {
                        wipeDataOnReset = it
                        prefs.edit().putBoolean("wipe_data", it).apply()
                    },
                    onToggleAutoGrant = {
                        autoGrantPerms = it
                        prefs.edit().putBoolean("auto_grant", it).apply()
                    },
                    onToggleRotatePreset = {
                        rotatePresetOnReset = it
                        prefs.edit().putBoolean("rotate_preset", it).apply()
                    },
                    onTogglePerApp = {
                        perAppIsolation = it
                        prefs.edit().putBoolean("per_app", it).apply()
                    },
                    onSelectPreset = {
                        selectedPresetIdx = it
                        prefs.edit().putInt("preset_idx", it).apply()
                    },
                    onTogglePin = { pkg ->
                        val updated = pinnedPackages.toMutableSet()
                        if (!updated.add(pkg)) updated.remove(pkg)
                        pinnedPackages = updated
                        prefs.edit().putStringSet("pinned_pkgs", updated).apply()
                        refreshApps()
                    },
                    onRunOneTap = { pkg, forceWipe ->
                        scope.launch {
                            val res = SpoofEngine.executeOneTapWorkflow(
                                context = context,
                                targetPkg = pkg,
                                wipeData = forceWipe,
                                autoGrantPermissions = autoGrantPerms,
                                rotatePreset = rotatePresetOnReset,
                                selectedPresetIndex = selectedPresetIdx,
                                perAppIsolation = perAppIsolation,
                                onStep = { step -> statusBanner = step }
                            )
                            statusBanner = res.fold(
                                onSuccess = { msg -> msg },
                                onFailure = { err -> "Failed: ${err.message}" }
                            )
                            refreshApps()
                        }
                    },
                    onQuickRootCmd = { label, cmd ->
                        scope.launch {
                            statusBanner = "Running: $label..."
                            val ok = withContext(Dispatchers.IO) { SpoofEngine.runRootCommands(cmd) }
                            statusBanner = if (ok) "$label complete" else "Root command failed"
                        }
                    },
                    onInspectAppHooks = { pkg ->
                        inspectedPkg = pkg
                        selectedTab = 1
                    }
                )
            } else {
                HooksEditorTab(
                    targetPkg = inspectedPkg,
                    selectedPresetIdx = selectedPresetIdx,
                    onClearTargetPkg = { inspectedPkg = null },
                    onStatusChange = { statusBanner = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsWorkflowTab(
    appList: List<AppEntry>,
    isLoading: Boolean,
    wipeData: Boolean,
    autoGrant: Boolean,
    rotatePreset: Boolean,
    perAppIsolation: Boolean,
    selectedPresetIdx: Int,
    onToggleWipe: (Boolean) -> Unit,
    onToggleAutoGrant: (Boolean) -> Unit,
    onToggleRotatePreset: (Boolean) -> Unit,
    onTogglePerApp: (Boolean) -> Unit,
    onSelectPreset: (Int) -> Unit,
    onTogglePin: (String) -> Unit,
    onRunOneTap: (String, Boolean) -> Unit,
    onQuickRootCmd: (String, String) -> Unit,
    onInspectAppHooks: (String) -> Unit
) {
    var filterMode by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showPipelineConfig by remember { mutableStateOf(false) }

    val filteredApps = remember(appList, filterMode, searchQuery) {
        val scopedCount = appList.count { it.isScoped || it.isPinned }
        appList.filter { app ->
            val matchesFilter = if (filterMode == 0 && scopedCount > 0) {
                app.isScoped || app.isPinned
            } else {
                true
            }
            val matchesQuery = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPipelineConfig = !showPipelineConfig },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "1-Tap Execution Pipeline",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = buildString {
                                append("Force Stop → ")
                                if (wipeData) append("Wipe Data → ")
                                append(if (rotatePreset) "Random Model + IDs" else SpoofEngine.PRESETS[selectedPresetIdx].title)
                                append(" → Open")
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Icon(
                        imageVector = if (showPipelineConfig) Icons.Default.ExpandLess else Icons.Default.Tune,
                        contentDescription = "Pipeline Options",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (showPipelineConfig) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFF263042))
                    PipelineSwitchRow("Wipe App Data (pm clear)", wipeData, onToggleWipe)
                    PipelineSwitchRow("Auto-Grant Runtime Permissions", autoGrant, onToggleAutoGrant)
                    PipelineSwitchRow("Rotate Device Model Preset on Reset", rotatePreset, onToggleRotatePreset)
                    PipelineSwitchRow("Per-App Isolated Identity (.conf)", perAppIsolation, onTogglePerApp)

                    if (!rotatePreset) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Locked Preset:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SpoofEngine.PRESETS.indices.toList()) { idx ->
                                FilterChip(
                                    selected = selectedPresetIdx == idx,
                                    onClick = { onSelectPreset(idx) },
                                    label = { Text(SpoofEngine.PRESETS[idx].title, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = filterMode == 0,
                onClick = { filterMode = 0 },
                label = { Text("Scoped & Pinned (${appList.count { it.isScoped || it.isPinned }})") }
            )
            FilterChip(
                selected = filterMode == 1,
                onClick = { filterMode = 1 },
                label = { Text("All Apps (${appList.size})") }
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search package or app name...", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppSpoofCard(
                        app = app,
                        defaultWipeData = wipeData,
                        onTogglePin = { onTogglePin(app.packageName) },
                        onFullResetSpoofAndOpen = { onRunOneTap(app.packageName, wipeData) },
                        onSoftSpoofAndOpen = { onRunOneTap(app.packageName, false) },
                        onForceStopOnly = {
                            onQuickRootCmd("Force Stop ${app.label}", "am force-stop ${app.packageName}")
                        },
                        onClearDataOnly = {
                            onQuickRootCmd("Clear Data ${app.label}", "pm clear ${app.packageName}")
                        },
                        onInspectHooks = { onInspectAppHooks(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
fun AppSpoofCard(
    app: AppEntry,
    defaultWipeData: Boolean,
    onTogglePin: () -> Unit,
    onFullResetSpoofAndOpen: () -> Unit,
    onSoftSpoofAndOpen: () -> Unit,
    onForceStopOnly: () -> Unit,
    onClearDataOnly: () -> Unit,
    onInspectHooks: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = app.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (app.isScoped) {
                            Badge(containerColor = Color(0xFF065F46), contentColor = Color(0xFF6EE7B7)) {
                                Text("LSPosed", modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                        if (app.hasCustomProfile) {
                            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                Text("Isolated ID", modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                    Text(
                        text = app.packageName,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row {
                    IconButton(onClick = onInspectHooks) {
                        Icon(
                            Icons.Default.EditNote,
                            contentDescription = "Inspect Hooks",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (app.isPinned) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Pin App",
                            tint = if (app.isPinned) Color(0xFFFBBF24) else Color(0xFF64748B)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onFullResetSpoofAndOpen,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (defaultWipeData) {
                        "Stop • Randomize • Wipe Data • Open"
                    } else {
                        "Stop • Randomize Hooks • Open"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSoftSpoofAndOpen,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Soft Spoof", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onForceStopOnly,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onClearDataOnly,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Wipe", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun PipelineSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun HooksEditorTab(
    targetPkg: String?,
    selectedPresetIdx: Int,
    onClearTargetPkg: () -> Unit,
    onStatusChange: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profileMap by remember(targetPkg) {
        mutableStateOf(SpoofEngine.readProfileMap(context, targetPkg))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (targetPkg == null) "Global Fallback Identity" else "App Identity: $targetPkg",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Preset: ${profileMap["preset.title"] ?: "Custom"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (targetPkg != null) {
                        TextButton(onClick = onClearTargetPkg) {
                            Text("Switch to Global")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val preset = SpoofEngine.PRESETS.getOrElse(selectedPresetIdx) {
                                    SpoofEngine.PRESETS.first()
                                }
                                val fresh = SpoofEngine.generateRandomProfileMap(preset)
                                profileMap = fresh
                                withContext(Dispatchers.IO) {
                                    SpoofEngine.saveAndSyncProfile(context, targetPkg, fresh)
                                }
                                onStatusChange("Randomized & synced ${targetPkg ?: "Global"} hooks!")
                                Toast.makeText(context, "Synced to Root Bridge!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Randomize & Sync")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    SpoofEngine.saveAndSyncProfile(context, targetPkg, profileMap)
                                }
                                onStatusChange("Saved edits for ${targetPkg ?: "Global"}!")
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val entries = profileMap.entries.toList()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(entries, key = { it.key }) { entry ->
                OutlinedTextField(
                    value = entry.value,
                    onValueChange = { newValue ->
                        val copy = LinkedHashMap(profileMap)
                        copy[entry.key] = newValue
                        profileMap = copy
                    },
                    label = { Text(entry.key, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
