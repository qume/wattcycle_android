package com.qume.wattcycle

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class BatteryData(
    val macAddress: String,
    val name: String,
    val soc: Int = 0,
    val voltage: Double = 0.0,
    val current: Double = 0.0,
    val temperature: Double = 0.0,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = adapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())

    private val _batteries = MutableStateFlow<Map<String, BatteryData>>(emptyMap())
    val batteries: StateFlow<Map<String, BatteryData>> = _batteries

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val gattConnections = mutableMapOf<String, BluetoothGatt>()
    private val rxBuffers = mutableMapOf<String, ByteArray>()
    private val detectedHead = mutableMapOf<String, Byte>()
    private var pollTick = 0

    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val AUTH_UUID = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun addLog(msg: String) {
        Log.d("Wattcycle", msg)
        _logs.value = (listOf(msg) + _logs.value).take(100)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            if (name.startsWith("XDZN") || name.startsWith("WT")) {
                val mac = device.address
                if (!_batteries.value.containsKey(mac)) {
                    addLog("App Version: 0.5.0")
                    addLog("Found device: $name ($mac)")
                    updateBattery(mac) { it ?: BatteryData(mac, name) }
                    connect(device)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            addLog("Scan failed: $errorCode")
        }
    }

    fun startScan() {
        if (scanner == null) {
            addLog("BLE Scanner not available")
            return
        }
        addLog("Starting scan...")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    fun stopScan() {
        addLog("Stopping scan...")
        scanner?.stopScan(scanCallback)
    }

    private fun updateBattery(mac: String, update: (BatteryData?) -> BatteryData) {
        _batteries.value = _batteries.value.toMutableMap().apply {
            put(mac, update(get(mac)))
        }
    }

    private fun connect(device: BluetoothDevice) {
        val mac = device.address
        updateBattery(mac) { it!!.copy(isConnecting = true) }
        addLog("Connecting to $mac...")
        val gatt = device.connectGatt(context, false, gattCallback)
        gattConnections[mac] = gatt
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addLog("Connected to $mac (status=$status), discovering services...")
                updateBattery(mac) { it!!.copy(isConnected = true, isConnecting = false) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("Disconnected from $mac (status=$status)")
                updateBattery(mac) { it!!.copy(isConnected = false, isConnecting = false) }
                gatt.close()
                gattConnections.remove(mac)
                rxBuffers.remove(mac)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val mac = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered for $mac")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val notifyChar = service.getCharacteristic(NOTIFY_UUID)
                    val writeChar = service.getCharacteristic(WRITE_UUID)
                    val authChar = service.getCharacteristic(AUTH_UUID)

                    addLog("GATT chars: notify=${notifyChar != null}(prop=${notifyChar?.properties}), write=${writeChar != null}(prop=${writeChar?.properties}), auth=${authChar != null}(prop=${authChar?.properties})")

                    if (notifyChar != null) {
                        val setNotify = gatt.setCharacteristicNotification(notifyChar, true)
                        addLog("setCharacteristicNotification=$setNotify")
                        val desc = notifyChar.getDescriptor(CCCD_UUID)
                        if (desc != null) {
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val writeDesc = gatt.writeDescriptor(desc)
                            addLog("writeDescriptor(CCCD)=$writeDesc")
                        } else {
                            addLog("CCCD not found, sending auth directly")
                            sendAuth(gatt)
                        }
                    } else {
                        addLog("NOTIFY_UUID not found")
                    }
                } else {
                    addLog("SERVICE_UUID not found")
                }
            } else {
                addLog("Service discovery failed: status=$status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor?, status: Int) {
            addLog("onDescriptorWrite: ${descriptor?.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == CCCD_UUID) {
                sendAuth(gatt)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            addLog("onCharWrite: ${characteristic.uuid}, status=$status")
        }

        // Android 13+ (API 33+) callback
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(gatt, characteristic, value)
        }

        // Android 12 and below callback
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: ByteArray(0)
            handleNotification(gatt, characteristic, v)
        }
    }

    private fun sendAuth(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val authChar = service?.getCharacteristic(AUTH_UUID)
        if (authChar != null) {
            val authPayload = WattcycleProtocol.AUTH_KEY
            authChar.value = authPayload
            authChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = gatt.writeCharacteristic(authChar)
            addLog("Sent HiLink auth: success=$success")

            // Wait 500ms then start polling (matches Python client: await asyncio.sleep(0.5))
            handler.postDelayed({
                startPolling(gatt)
            }, 500L)
        } else {
            addLog("AUTH_UUID not found")
        }
    }

    private fun startPolling(gatt: BluetoothGatt) {
        val runnable = object : Runnable {
            override fun run() {
                val mac = gatt.device.address
                if (gattConnections.containsKey(mac)) {
                    pollDevice(gatt)
                    handler.postDelayed(this, 5000L)
                }
            }
        }
        handler.post(runnable)
    }

    private fun pollDevice(gatt: BluetoothGatt) {
        val mac = gatt.device.address
        val head = detectedHead[mac] ?: run {
            // If head not yet confirmed, alternate between 0x7E and 0x1E
            if ((pollTick++ % 2) == 0) WattcycleProtocol.FRAME_HEAD else WattcycleProtocol.FRAME_HEAD_ALT
        }

        // Send read request
        val frame = WattcycleProtocol.buildReadFrame(WattcycleProtocol.DP_ANALOG_QUANTITY, 0, head)
        sendGattCommand(gatt, frame)
    }

    private fun sendGattCommand(gatt: BluetoothGatt, cmd: ByteArray) {
        val service = gatt.getService(SERVICE_UUID)
        val writeChar = service?.getCharacteristic(WRITE_UUID)
        if (writeChar != null) {
            writeChar.value = cmd
            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = gatt.writeCharacteristic(writeChar)
            val hex = cmd.joinToString("") { "%02X".format(it) }
            addLog("TX: success=$success, $hex")
        } else {
            addLog("WRITE_UUID not found")
        }
    }

    private fun handleNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, chunk: ByteArray) {
        val mac = gatt.device.address
        val chunkHex = chunk.joinToString("") { "%02X".format(it) }
        addLog("RX (${chunk.size}b): $chunkHex")

        if (characteristic.uuid != NOTIFY_UUID) return

        var buffer = rxBuffers[mac] ?: ByteArray(0)
        buffer += chunk

        val expectedLen = WattcycleProtocol.expectedResponseLength(buffer)
        if (expectedLen != null && buffer.size >= expectedLen) {
            val packet = buffer.copyOfRange(0, expectedLen)
            rxBuffers[mac] = buffer.copyOfRange(expectedLen, buffer.size)
            processPacket(gatt.device, packet)
        } else {
            rxBuffers[mac] = buffer
        }
    }

    private fun processPacket(device: BluetoothDevice, packet: ByteArray) {
        val mac = device.address
        val hex = packet.joinToString("") { "%02X".format(it) }
        addLog("Packet (${packet.size}b): $hex")

        if (packet.size < WattcycleProtocol.MIN_FRAME_SIZE) {
            addLog("Packet too short")
            return
        }

        if (!WattcycleProtocol.verifyCrc(packet)) {
            addLog("CRC verification failed!")
            return
        }

        // Lock onto detected frame head (0x7E or 0x1E)
        val head = packet[0]
        detectedHead[mac] = head

        val func = packet[3]
        if (func == 0x86.toByte()) {
            addLog("Device returned error (0x86)")
            return
        }
        if (func != WattcycleProtocol.FUNC_READ) {
            addLog("Not a read response: func=$func")
            return
        }

        val startAddr = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val dataLen = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        val data = packet.copyOfRange(8, 8 + dataLen)

        when (startAddr) {
            WattcycleProtocol.DP_ANALOG_QUANTITY -> {
                val parsed = WattcycleProtocol.parseAnalogQuantity(data)
                if (parsed != null) {
                    val vStr = String.format("%.2f", parsed.moduleVoltage)
                    val cStr = String.format("%.1f", parsed.current)
                    val tStr = String.format("%.1f", parsed.mosTemperature)
                    addLog("Parsed: ${parsed.soc}%, ${vStr}V, ${cStr}A, ${tStr}°C")
                    updateBattery(mac) {
                        it!!.copy(
                            soc = parsed.soc,
                            voltage = parsed.moduleVoltage,
                            current = parsed.current,
                            temperature = parsed.mosTemperature
                        )
                    }
                } else {
                    addLog("Failed to parse Analog Quantity payload")
                }
            }
            WattcycleProtocol.DP_PRODUCT_INFO -> {
                val info = WattcycleProtocol.parseProductInfo(data)
                if (info != null) {
                    addLog("Product: FW=${info.firmwareVersion}, SN=${info.serialNumber}")
                }
            }
            else -> {
                addLog("Response for address 0x${"%04X".format(startAddr)}")
            }
        }
    }
}
