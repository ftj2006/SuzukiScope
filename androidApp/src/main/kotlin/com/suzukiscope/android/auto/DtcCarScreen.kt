package com.suzukiscope.android.auto

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
import com.suzukiscope.android.AppState
import kotlinx.coroutines.launch

/** Reads the engine ECU's fault codes, using the same connection as the phone app. Clearing is phone-only (it needs a confirmation). */
class DtcCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycleScope.launch { AppState.dtcViewModel.engineStatus.collect { invalidate() } }
                lifecycleScope.launch { AppState.dtcViewModel.engineResults.collect { invalidate() } }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val status = AppState.dtcViewModel.engineStatus.value
        val results = AppState.dtcViewModel.engineResults.value.orEmpty()

        val itemListBuilder = ItemList.Builder()
        itemListBuilder.addItem(Row.Builder().setTitle("Engine").addText(status).build())
        for (result in results) {
            for (code in result.codes) {
                itemListBuilder.addItem(
                    Row.Builder().setTitle("${code.code} \u00B7 ${result.source.label}").addText(code.description ?: "No description").build(),
                )
            }
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Read codes")
                    .setOnClickListener { lifecycleScope.launch { AppState.dtcViewModel.readEngine() } }
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
