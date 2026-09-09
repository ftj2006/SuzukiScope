package com.suzukiscope.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.suzukiscope.core.field.FieldCatalogLoader

/**
 * Ties Dashboard/Config/Diagnostic-codes together with a tab row so both apps get the
 * same navigation for free instead of duplicating it per platform.
 */
@Composable
fun AppScreen(
    dashboardViewModel: DashboardViewModel,
    dtcViewModel: DtcViewModel,
    onExportCsv: (files: List<DashboardViewModel.ExportFile>) -> Unit,
    connectionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    val fields by dashboardViewModel.fields.collectAsState()

    // The full 588-field extraction catalog loads in (disabled by default) the first time the
    // Configure fields tab is opened, instead of requiring a manual button press.
    LaunchedEffect(tab) {
        if (tab == 2) dashboardViewModel.loadFullCatalog(FieldCatalogLoader.loadFullCatalog())
    }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Outlined.Dashboard, contentDescription = "Dashboard") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Outlined.History, contentDescription = "History") },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                icon = { Icon(Icons.Outlined.Tune, contentDescription = "Configure fields") },
            )
            Tab(
                selected = tab == 3,
                onClick = { tab = 3 },
                icon = { Icon(Icons.Outlined.Warning, contentDescription = "Diagnostic trouble codes") },
            )
        }
        when (tab) {
            0 -> DashboardScreen(dashboardViewModel, onExportCsv, connectionBar, Modifier.fillMaxSize())
            1 -> HistoryScreen(dashboardViewModel, Modifier.fillMaxSize())
            2 -> ConfigScreen(dashboardViewModel, Modifier.fillMaxSize())
            else -> DtcScreen(dtcViewModel, moduleOptionsFrom(fields), Modifier.fillMaxSize())
        }
    }
}
