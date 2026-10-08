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
                    addLog("App Version: 0.3.0")
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
                authChar.setValue(authPayload)
                gatt.writeCharacteristic(authChar)
                addLog("Sent HiLink auth to ${gatt.device.address}")
                addLog("Auth payload size: ${authPayload.size}")
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
            // The python library does NOT use new frame! It uses old frame format. Let's revert to old frame
            // TX: 7E 00 01 03 00 8C 00 00 [CRC_HI] [CRC_LO] 0D
            val cmd = byteArrayOf(0x7E.toByte(), 0x00.toByte(), 0x01.toByte(), 0x03.toByte(), 0x00.toByte(), 0x8C.toByte(), 0x00.toByte(), 0x00.toByte())
            val crc = modbusCrc16(cmd)
            val fullCmd = cmd + byteArrayOf((crc shr 8).toByte(), (crc and 0xFF).toByte(), 0x0D.toByte()) // CRC is already big-endian (lo << 8 | hi)
            addLog("TX: " + fullCmd.joinToString("") { "%02X".format(it) })
            writeChar.setValue(fullCmd)
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
        val head = packet[0].toInt() and 0xFF
        if (head != 0x7E && head != 0x1E) {
            addLog("Invalid frame head: ${"%02X".format(head)}")
            return
        }
        if (packet.size < 11) {
            addLog("Packet too short: size ${packet.size}")
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

    private val CRC_HI = intArrayOf(
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40, 0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41,
        0x00, 0xC1, 0x81, 0x40, 0x01, 0xC0, 0x80, 0x41, 0x01, 0xC0, 0x80, 0x41, 0x00, 0xC1, 0x81, 0x40
    )
    private val CRC_LO = intArrayOf(
        0x00, 0xC0, 0xC1, 0x01, 0xC3, 0x03, 0x02, 0xC2, 0xC6, 0x06, 0x07, 0xC7, 0x05, 0xC5, 0xC4, 0x04,
        0xCC, 0x0C, 0x0D, 0xCD, 0x0F, 0xCF, 0xCE, 0x0E, 0x0A, 0xCA, 0xCB, 0x0B, 0xC9, 0x09, 0x08, 0xC8,
        0xD8, 0x18, 0x19, 0xD9, 0x1B, 0xDB, 0xDA, 0x1A, 0x1E, 0xDE, 0xDF, 0x1F, 0xDD, 0x1D, 0x1C, 0xDC,
        0x14, 0xD4, 0xD5, 0x15, 0xD7, 0x17, 0x16, 0xD6, 0xD2, 0x12, 0x13, 0xD3, 0x11, 0xD1, 0xD0, 0x10,
        0xF0, 0x30, 0x31, 0xF1, 0x33, 0xF3, 0xF2, 0x32, 0x36, 0xF6, 0xF7, 0x37, 0xF5, 0x35, 0x34, 0xF4,
        0x3C, 0xFC, 0xFD, 0x3D, 0xFF, 0x3F, 0x3E, 0xFE, 0xFA, 0x3A, 0x3B, 0xFB, 0x39, 0xF9, 0xF8, 0x38,
        0x28, 0xE8, 0xE9, 0x29, 0xEB, 0x2B, 0x2A, 0xEA, 0xEE, 0x2E, 0x2F, 0xEF, 0x2D, 0xED, 0xEC, 0x2C,
        0xE4, 0x24, 0x25, 0xE5, 0x27, 0xE7, 0xE6, 0x26, 0x22, 0xE2, 0xE3, 0x23, 0xE1, 0x21, 0x20, 0xE0,
        0xA0, 0x60, 0x61, 0xA1, 0x63, 0xA3, 0xA2, 0x62, 0x66, 0xA6, 0xA7, 0x67, 0xA5, 0x65, 0x64, 0xA4,
        0x6C, 0xAC, 0xAD, 0x6D, 0xAF, 0x6F, 0x6E, 0xAE, 0xAA, 0x6A, 0x6B, 0xAB, 0x69, 0xA9, 0xA8, 0x68,
        0x78, 0xB8, 0xB9, 0x79, 0xBB, 0x7B, 0x7A, 0xBA, 0xBE, 0x7E, 0x7F, 0xBF, 0x7D, 0xBD, 0xBC, 0x7C,
        0xB4, 0x74, 0x75, 0xB5, 0x77, 0xB7, 0xB6, 0x76, 0x72, 0xB2, 0xB3, 0x73, 0xB1, 0x71, 0x70, 0xB0,
        0x50, 0x90, 0x91, 0x51, 0x93, 0x53, 0x52, 0x92, 0x96, 0x56, 0x57, 0x97, 0x55, 0x95, 0x94, 0x54,
        0x9C, 0x5C, 0x5D, 0x9D, 0x5F, 0x9F, 0x9E, 0x5E, 0x5A, 0x9A, 0x9B, 0x5B, 0x99, 0x59, 0x58, 0x98,
        0x88, 0x48, 0x49, 0x89, 0x4B, 0x8B, 0x8A, 0x4A, 0x4E, 0x8E, 0x8F, 0x4F, 0x8D, 0x4D, 0x4C, 0x8C,
        0x44, 0x84, 0x85, 0x45, 0x87, 0x47, 0x46, 0x86, 0x82, 0x42, 0x43, 0x83, 0x41, 0x81, 0x80, 0x40
    )

    private fun modbusCrc16(data: ByteArray): Int {
        var crcHi = 0xFF
        var crcLo = 0xFF
        for (byte in data) {
            val index = crcHi xor (byte.toInt() and 0xFF)
            crcHi = crcLo xor CRC_HI[index]
            crcLo = CRC_LO[index]
        }
        return ((crcLo and 0xFF) shl 8) or (crcHi and 0xFF)
    }
}
