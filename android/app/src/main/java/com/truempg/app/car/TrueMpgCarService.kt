package com.truempg.app.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.truempg.app.obd.ObdRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Android Auto entry point. Projects a small live pane (avg/instant MPG, speed,
 * coolant, status) onto the head unit. NOTE: works only with Android Auto in
 * developer mode ("Unknown sources") for a sideloaded build; production use
 * needs Play review/allowlisting.
 */
class TrueMpgCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = TrueMpgCarScreen(carContext)
    }
}

private class TrueMpgCarScreen(carContext: CarContext) : Screen(carContext) {
    private var job: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Refresh the template as data changes (sampled; host also throttles).
                job = owner.lifecycleScope.launch {
                    ObdRepository.state.sample(2000).collect { invalidate() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel(); job = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val s = ObdRepository.state.value
        val avg = if (s.tripGallons > 1e-6) s.tripMiles / s.tripGallons else 0.0
        val speed = if (s.distanceUnit == "KMH") "%.0f km/h".format(s.readings.mph * 1.609344)
        else "%.0f mph".format(s.readings.mph)
        val coolant = s.readings.coolantC?.let {
            if (s.tempUnit == "F") "%.0f F".format(it * 9 / 5 + 32) else "%.0f C".format(it)
        } ?: "--"

        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Avg MPG").addText("%.1f".format(avg)).build())
            .addRow(Row.Builder().setTitle("Instant MPG").addText("%.1f".format(s.readings.instMpg)).build())
            .addRow(Row.Builder().setTitle("Speed").addText(speed).build())
            .addRow(Row.Builder().setTitle("Coolant").addText(coolant).build())
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(if (s.connected) "TrueMPG" else "TrueMPG — ${s.status}")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
