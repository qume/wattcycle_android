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
import kotlin.experimental.and
import kotlin.experimental.xor

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

    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val AUTH_UUID = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb")

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

    // A map to accumulate chunks for a device.
    private val rxBuffers = mutableMapOf<String, ByteArray>()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                addLog("Connected to $mac, discovering services...")
                updateBattery(mac) { it!!.copy(isConnected = true, isConnecting = false) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("Disconnected from $mac (status $status)")
                updateBattery(mac) { it!!.copy(isConnected = false, isConnecting = false) }
                gatt.close()
                gattConnections.remove(mac)
                // Reconnect? For simplicity, we just leave it disconnected until next scan? Or auto-reconnect.
                // handler.postDelayed({ connect(gatt.device) }, 5000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val mac = gatt.device.address
                addLog("Services discovered for $mac")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val notifyChar = service.getCharacteristic(NOTIFY_UUID)
                    if (notifyChar != null) {
                        gatt.setCharacteristicNotification(notifyChar, true)
                        // In BLE, usually we also need to write to CCCD 0x2902, but some devices don't require it or it's implicitly handled.
                        // We'll write CCCD just in case
                        val desc = notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (desc != null) {
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(desc)
                        } else {
                            // Proceed to Auth
                            sendAuth(gatt)
                        }
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendAuth(gatt)
            }
        }

        private fun sendAuth(gatt: BluetoothGatt) {
            val service = gatt.getService(SERVICE_UUID)
            val authChar = service?.getCharacteristic(AUTH_UUID)
            if (authChar != null) {
                val authPayload = "HiLink".toByteArray(Charsets.UTF_8)
                authChar.value = authPayload
                gatt.writeCharacteristic(authChar)
                addLog("Sent HiLink auth to ${gatt.device.address}")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == AUTH_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Auth success for ${gatt.device.address}, requesting Analog Quantity...")
                // Start requesting loop
                requestAnalogQuantity(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == NOTIFY_UUID) {
                addLog("Received chunk of size ${characteristic.value.size}")
                val mac = gatt.device.address
                val chunk = characteristic.value
                var buffer = rxBuffers[mac] ?: ByteArray(0)
                buffer += chunk
                
                if (buffer.isNotEmpty() && buffer.size > 8) {
                    val dataLen = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
                    val expectedLen = dataLen + 11
                    if (buffer.size >= expectedLen) {
                        val packet = buffer.copyOfRange(0, expectedLen)
                        addLog("Complete packet assembled: size ${packet.size}")
                        rxBuffers[mac] = buffer.copyOfRange(expectedLen, buffer.size)
                        parsePacket(gatt.device, packet)
                    } else {
                        rxBuffers[mac] = buffer
                    }
                } else {
                    rxBuffers[mac] = buffer
                }
            }
        }
    }

    private fun requestAnalogQuantity(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val writeChar = service?.getCharacteristic(WRITE_UUID)
        if (writeChar != null) {
            // TX: 7E 00 01 03 00 8C 00 00 [CRC_HI] [CRC_LO] 0D
            val cmd = byteArrayOf(0x7E.toByte(), 0x00.toByte(), 0x01.toByte(), 0x03.toByte(), 0x00.toByte(), 0x8C.toByte(), 0x00.toByte(), 0x00.toByte())
            val crc = modbusCrc16(cmd)
            val fullCmd = cmd + byteArrayOf((crc shr 8).toByte(), (crc and 0xFF).toByte(), 0x0D.toByte())
            addLog("TX: " + fullCmd.joinToString("") { "%02X".format(it) })
            writeChar.value = fullCmd
            gatt.writeCharacteristic(writeChar)
            
            // Re-request every 5 seconds
            handler.postDelayed({
                if (gattConnections.containsKey(gatt.device.address)) {
                    requestAnalogQuantity(gatt)
                }
            }, 5000)
        }
    }

    private fun parsePacket(device: BluetoothDevice, packet: ByteArray) {
        addLog("RX: " + packet.joinToString("") { "%02X".format(it) })
        if (packet.size < 11) {
            addLog("Packet too short")
            return
        }
        val func = packet[3]
        if (func != 0x03.toByte()) {
            addLog("Not a read response (func = $func)")
            return
        }
        
        val startAddr = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (startAddr != 0x008C) {
            addLog("Not Analog Quantity (addr = ${"%04X".format(startAddr)})")
            return
        }
        
        val dataLen = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        if (packet.size < 8 + dataLen) {
            addLog("Data len mismatch")
            return
        }
        
        val data = packet.copyOfRange(8, 8 + dataLen)
        
        // Parse according to Protocol.md
        // 0: cellCount
        val cellCount = data[0].toInt() and 0xFF
        var offset = 1 + (cellCount * 2)
        // next: temperatureCount
        val tempCount = data[offset].toInt() and 0xFF
        offset += 1
        
        val mosTempRaw = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset+1].toInt() and 0xFF)
        val pcbTempRaw = ((data[offset+2].toInt() and 0xFF) shl 8) or (data[offset+3].toInt() and 0xFF)
        val mosTemp = (mosTempRaw - 2730) / 10.0
        val pcbTemp = (pcbTempRaw - 2730) / 10.0
        
        offset += 2 * tempCount
        
        // Current: custom parser
        val curB0 = data[offset]
        val curB1 = data[offset+1].toInt() and 0xFF
        val isNegative = (curB0.toInt() and 0x80) != 0
        val hasDecimal = (curB0.toInt() and 0x40) != 0
        val curRaw = curB1 or ((curB0.toInt() and 0x3F) shl 8)
        var current = if (hasDecimal) curRaw / 10.0 else curRaw.toDouble()
        if (isNegative) current = -current
        
        offset += 2
        
        val modVolRaw = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset+1].toInt() and 0xFF)
        val voltage = modVolRaw / 100.0
        
        offset += 8
        val soc = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset+1].toInt() and 0xFF)
        
        updateBattery(device.address) { 
            it!!.copy(
                soc = soc, 
                voltage = voltage, 
                current = current, 
                temperature = mosTemp 
            ) 
        }
    }

    private fun modbusCrc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                if (crc and 1 != 0) {
                    crc = (crc shr 1) xor 0xA001
                } else {
                    crc = crc shr 1
                }
            }
        }
        // Swap bytes for big-endian insertion as per python code: (lo << 8) | hi
        return ((crc and 0xFF) shl 8) or ((crc shr 8) and 0xFF)
    }
}
