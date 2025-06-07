package ru.yandexpraktikum.blechat.presentation.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.bluetooth.BleServerController
import javax.inject.Inject

@HiltViewModel
class ScannedDevicesViewModel @Inject constructor(
    private val serverController: BleServerController,
    private val clientController: BleClientController
    ): ViewModel() {

    private val _state = MutableStateFlow(ScannedDevicesState())

    val state = combine(
        serverController.connectedDevices,
        clientController.scannedDevices,
        clientController.isBluetoothEnabled,
        clientController.isLocationEnabled,
        _state
    ) { connectedDevices, scannedDevices, isBluetoothEnabled, isLocationEnabled, state ->
        state.copy(
            connectedDevices = connectedDevices.map {
                it.copy(isConnected = true)
            },
            scannedDevices = scannedDevices,
            isBluetoothEnabled = isBluetoothEnabled,
            isLocationEnabled = isLocationEnabled,
            isScanning = state.isScanning,
            isAdvertising = state.isAdvertising
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ScannedDevicesState()
    )

    fun onEvent(event: ScannedDevicesEvent) {
        when (event) {
            is ScannedDevicesEvent.ToggleAdvertising -> {
                if (!state.value.isBluetoothEnabled) {
                    _state.update { it.copy(errorMessage = "Bluetooth is not enabled") }
                    return
                }
                _state.update { it.copy(isAdvertising = !it.isAdvertising) }
                if (_state.value.isAdvertising) {
                    startAdvertising()
                } else {
                    serverController.stopServer()
                }
            }
            is ScannedDevicesEvent.ToggleScan -> {
                if (!state.value.isBluetoothEnabled) {
                    _state.update { it.copy(errorMessage = "Bluetooth is not enabled") }
                    return
                }
                _state.update { it.copy(isScanning = !it.isScanning) }
                if (_state.value.isScanning) {
                    startScan()
                } else {
                    clientController.stopScan()
                }
            }

            is ScannedDevicesEvent.ConnectToDevice -> {
                clientController.connectToDevice(event.device)
            }
            is ScannedDevicesEvent.CheckLocationSettings -> {
                clientController.updateLocationState()
            }
            is ScannedDevicesEvent.CheckBluetoothSettings -> {
                clientController.updateBluetoothState()
            }
        }
    }

    private fun startScan() {
        viewModelScope.launch {
            try {
                clientController.startScan()
                delay(SCAN_PERIOD)
                if (_state.value.isScanning) {
                    _state.update { it.copy(isScanning = false) }
                    clientController.stopScan()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "Failed to scan: ${e.localizedMessage}"
                    )
                }
                clientController.stopScan()
            }
        }
    }


    private fun startAdvertising() {
        viewModelScope.launch {
            try {
                serverController.startServer()
                delay(ADVERTISE_PERIOD)
                if (_state.value.isAdvertising && serverController.connectedDevices.value.isEmpty()) {
                    _state.update { it.copy(isAdvertising = false) }
                    serverController.stopServer()
                } else if (_state.value.isAdvertising){
                    _state.update { it.copy(isAdvertising = false) }
                    serverController.stopAdvertising()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isAdvertising = false,
                        errorMessage = "Failed to advertise: ${e.localizedMessage}"
                    )
                }
                serverController.stopServer()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        clientController.stopScan()
        serverController.stopAdvertising()
        clientController.release()
        serverController.release()
    }

    companion object {
        private const val SCAN_PERIOD = 150000L
        private const val ADVERTISE_PERIOD = 150000L
    }
}

