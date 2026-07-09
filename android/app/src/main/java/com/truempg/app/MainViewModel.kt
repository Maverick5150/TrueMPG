package com.truempg.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.truempg.app.obd.ObdMath.MpgMethod
import com.truempg.app.obd.ObdRepository
import com.truempg.app.service.ObdService
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI-facing wrapper. All real work lives in [ObdRepository] (process-wide,
 * so the foreground service can run it headless). Connect/disconnect go through
 * [ObdService] so monitoring survives the UI being closed.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    init { ObdRepository.init(app) }

    val state: StateFlow<UiState> = ObdRepository.state

    fun refreshDevices() = ObdRepository.refreshDevices()
    fun setVe(v: Double) = ObdRepository.setVe(v)
    fun setAutoConnect(enabled: Boolean) = ObdRepository.setAutoConnect(enabled)
    fun setDistanceUnit(u: String) = ObdRepository.setDistanceUnit(u)
    fun setEconomyUnit(u: String) = ObdRepository.setEconomyUnit(u)
    fun setTempUnit(u: String) = ObdRepository.setTempUnit(u)
    fun setDisplacement(liters: Double) = ObdRepository.setDisplacement(liters)
    fun setMethodOverride(method: MpgMethod?) = ObdRepository.setMethodOverride(method)

    fun connect(address: String) = ObdService.connect(getApplication<Application>(), address)
    fun disconnect() = ObdService.stop(getApplication<Application>())

    fun setAlertsEnabled(enabled: Boolean) = ObdRepository.setAlertsEnabled(enabled)
    fun setCoolantMaxF(f: Double) = ObdRepository.setCoolantMaxF(f)
    fun setLowFuelPct(pct: Double) = ObdRepository.setLowFuelPct(pct)

    fun startTrip() = ObdRepository.startTrip()
    fun stopTrip() = ObdRepository.stopTrip()
    fun clearTripHistory() = ObdRepository.clearTripHistory()
    fun readDtcs() = ObdRepository.readDtcs()
    fun clearDtcs() = ObdRepository.clearDtcs()
    fun readDiagnostics() = ObdRepository.readDiagnostics()

    fun logFillup(gallons: Double, pricePerGal: Double) = ObdRepository.logFillup(gallons, pricePerGal)
    fun setCalProfile(name: String) = ObdRepository.setCalProfile(name)
    fun addCalProfile(name: String) = ObdRepository.addCalProfile(name)

    fun setBlackBoxEnabled(enabled: Boolean) = ObdRepository.setBlackBoxEnabled(enabled)
    fun analyzeDtc(code: String) = ObdRepository.analyzeDtc(code)

    fun setKeepScreenOn(on: Boolean) = ObdRepository.setKeepScreenOn(on)
    fun markServiceDone(name: String) = ObdRepository.markServiceDone(name)
    fun addServiceItem(name: String, intervalMiles: Int) = ObdRepository.addServiceItem(name, intervalMiles)
    fun setOdometer(miles: Double) = ObdRepository.setOdometer(miles)
    fun queryEnhanced(pidHex: String) = ObdRepository.queryEnhanced(pidHex)
}
