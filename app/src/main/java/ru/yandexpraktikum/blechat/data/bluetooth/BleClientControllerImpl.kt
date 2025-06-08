package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.yandexpraktikum.blechat.R
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelper
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import ru.yandexpraktikum.blechat.utils.writeCharUUID
import java.nio.charset.Charset
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val viewModelScope: CoroutineScope,
    private val notificationsHelper: NotificationsHelper
) : BleClientController {

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }
    var bluetoothGatt: BluetoothGatt? = null

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()


    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        gatt?.let { updateScannedDevices(it, true) }
                        context.checkForConnectPermission {
                            gatt?.discoverServices()
                        }
                    }

                    BluetoothGatt.STATE_DISCONNECTED -> {
                        gatt?.let { updateScannedDevices(it, false) }
                        closeConnection()
                    }
                }
            } else {
                Log.e(TAG, "Failed connection")
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed get services")
            } else {
                val gattServiceList = gatt?.services
                val messageService = gattServiceList?.find { it.uuid == serviceUUID }
                val notifyChar = messageService?.getCharacteristic(notifyCharUUID)
                context.checkForConnectPermission {
                    gatt?.setCharacteristicNotification(notifyChar, true)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothStatusCodes.SUCCESS) {
                val clientAddress = bluetoothAdapter?.address
                val message = String(value, Charset.defaultCharset())
                _scannedDevices.update { scannedDeviceList ->
                    scannedDeviceList.map { device ->
                        if (device.address == clientAddress) {
                            device.addLocalMessage(message, clientAddress)
                        } else {
                            device
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed read the characteristic")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val message = characteristic?.let { String(it.value, Charsets.UTF_8) } ?: ""
            _scannedDevices.update { scannedDeviceList ->
                scannedDeviceList.map { device ->
                    if (device.address == gatt?.device?.address) {
                        device.addRemoteMessage(message, gatt.device.address)
                    } else {
                        device
                    }
                }
            }
            notificationsHelper.notifyOnMessageReceived(
                context.resources.getString(R.string.new_message),
                message
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                context.checkForConnectPermission {
                    gatt?.readCharacteristic(characteristic)
                }
            } else {
                Log.e(TAG, "Failed write to the characteristic")
            }


        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value =
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Location state", e)
        }
    }

    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        val bluetoothDevice =
            bluetoothAdapter?.getRemoteDevice(device.address)
        context.checkForConnectPermission {
            bluetoothGatt = bluetoothDevice?.connectGatt(context, false, gattCallback)
        }
        return bluetoothGatt != null
    }

    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        var isSuccess: Boolean? = null
        val writeMessageChar = bluetoothGatt?.getService(serviceUUID)
            .let { it?.getCharacteristic(writeCharUUID) }

        context.checkForConnectPermission {
            isSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = writeMessageChar?.let {
                    bluetoothGatt?.writeCharacteristic(
                        it,
                        message.toByteArray(Charset.defaultCharset()),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                }
                result == BluetoothStatusCodes.SUCCESS
            } else {
                writeMessageChar?.value = message.toByteArray(Charset.defaultCharset())
                writeMessageChar?.let { bluetoothGatt?.writeCharacteristic(it) }
            }
        }
        return isSuccess == true
    }

    override fun closeConnection() {
        context.checkForConnectPermission {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
    }

    override fun release() {
        closeConnection()
    }

    private fun updateScannedDevices(gatt: BluetoothGatt, isConnected: Boolean) {
        viewModelScope.launch {
            _scannedDevices.update {
                it.map { scannedDevice ->
                    if (gatt.device?.address == scannedDevice.address) {
                        scannedDevice.copy(isConnected = isConnected)
                    } else {
                        scannedDevice
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "BLE"
    }
}