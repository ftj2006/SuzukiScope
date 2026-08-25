package com.suzukiscan.android.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.suzukiscan.android.AppState
import com.suzukiscan.ui.moduleOptionsFrom
import kotlinx.coroutines.launch

/** Read/clear DTCs for the first available module, using the same connection as the phone app. */
class DtcCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch { AppState.dtcViewModel.status.collect { invalidate() } }
                lifecycleScope.launch { AppState.dtcViewModel.codes.collect { invalidate() } }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val module = moduleOptionsFrom(AppState.dashboardViewModel.fields.value).firstOrNull()
        val status = AppState.dtcViewModel.status.value
        val codes = AppState.dtcViewModel.codes.value

        val itemListBuilder = ItemList.Builder()
        itemListBuilder.addItem(Row.Builder().setTitle("Module").addText(module?.label ?: "None connected").build())
        itemListBuilder.addItem(Row.Builder().setTitle("Status").addText(status).build())
        for (code in codes) {
            itemListBuilder.addItem(Row.Builder().setTitle(code.code).addText(code.description ?: "No description").build())
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Read codes")
                    .setOnClickListener {
                        module?.let {
                            lifecycleScope.launch { AppState.dtcViewModel.readCodes(it.targetAddress, it.isFunctionalAddress, it.isCan) }
                        }
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Clear codes")
                    .setOnClickListener {
                        module?.let {
                            lifecycleScope.launch { AppState.dtcViewModel.clearCodes(it.targetAddress, isFunctionalAddress = false, isCan = it.isCan) }
                        }
                    }
                    .build(),
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("DTC")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }
}
