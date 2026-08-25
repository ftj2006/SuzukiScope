package com.suzukiscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.suzukiscan.core.field.FieldCatalogLoader

/**
 * Ties Dashboard/Config/Diagnostic-codes together with a tab row so both apps get the
 * same navigation for free instead of duplicating it per platform.
 */
@Composable
fun AppScreen(
    dashboardViewModel: DashboardViewModel,
    dtcViewModel: DtcViewModel,
    onExportCsv: (fileName: String, csv: String) -> Unit,
    onExportLog: (fileName: String, log: String) -> Unit = { _, _ -> },
    connectionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    val fields by dashboardViewModel.fields.collectAsState()

    // The full 588-field extraction catalog loads in (disabled by default) the first time the
    // Configure fields tab is opened, instead of requiring a manual button press.
    LaunchedEffect(tab) {
        if (tab == 1) dashboardViewModel.loadFullCatalog(FieldCatalogLoader.loadFullCatalog())
    }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Dashboard") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Configure fields") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("DTC") })
        }
        when (tab) {
            0 -> DashboardScreen(dashboardViewModel, onExportCsv, onExportLog, connectionBar, Modifier.fillMaxSize())
            1 -> ConfigScreen(dashboardViewModel, Modifier.fillMaxSize())
            else -> DtcScreen(dtcViewModel, moduleOptionsFrom(fields), Modifier.fillMaxSize())
        }
    }
}
