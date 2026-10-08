package com.qume.wattcycle

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {

    // Real captured response: Warning Info from XDZN_001_EF2F
    private val SAMPLE_WARNING_RESPONSE = byteArrayOf(
        0x7E.toByte(), 0x00.toByte(), 0x01.toByte(), 0x03.toByte(), 0x00.toByte(), 0x8D.toByte(), 0x00.toByte(), 0x18.toByte(),
        0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x06.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x18.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x1F.toByte(), 0x91.toByte(), 0x0D.toByte()
    )

    // Real captured response: Analog Quantity from XDZN_001_EF2F
    private val SAMPLE_ANALOG_RESPONSE = byteArrayOf(
        0x7E.toByte(), 0x00.toByte(), 0x01.toByte(), 0x03.toByte(), 0x00.toByte(), 0x8C.toByte(), 0x00.toByte(), 0x20.toByte(),
        0x04.toByte(), 0x0C.toByte(), 0xDE.toByte(), 0x0C.toByte(), 0xDD.toByte(), 0x0C.toByte(), 0xDF.toByte(), 0x0C.toByte(),
        0xDA.toByte(), 0x04.toByte(), 0x0B.toByte(), 0x65.toByte(), 0x0B.toByte(), 0x70.toByte(), 0x0B.toByte(), 0x5A.toByte(),
        0x0B.toByte(), 0x5A.toByte(), 0x40.toByte(), 0x00.toByte(), 0x05.toByte(), 0x25.toByte(), 0x07.toByte(), 0x2A.toByte(),
        0x0C.toByte(), 0x44.toByte(), 0x00.toByte(), 0x05.toByte(), 0x0C.toByte(), 0x44.toByte(), 0x00.toByte(), 0x3A.toByte(),
        0x4B.toByte(), 0x22.toByte(), 0x0D.toByte()
    )

    // Real captured response: Product Info from XDZN_001_EF2F
    private val SAMPLE_PRODUCT_RESPONSE = byteArrayOf(
        0x7E.toByte(), 0x00.toByte(), 0x01.toByte(), 0x03.toByte(), 0x00.toByte(), 0x92.toByte(), 0x00.toByte(), 0x3C.toByte(),
        0x57.toByte(), 0x54.toByte(), 0x31.toByte(), 0x32.toByte(), 0x5F.toByte(), 0x32.toByte(), 0x30.toByte(), 0x30.toByte(),
        0x30.toByte(), 0x34.toByte(), 0x53.toByte(), 0x57.toByte(), 0x31.toByte(), 0x30.toByte(), 0x5F.toByte(), 0x4C.toByte(),
        0x34.toByte(), 0x34.toByte(), 0x37.toByte(), 0x00.toByte(), 0x20.toByte(), 0x20.toByte(), 0x20.toByte(), 0x20.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x36.toByte(), 0x30.toByte(), 0x30.toByte(), 0x31.toByte(), 0x36.toByte(), 0x30.toByte(), 0x31.toByte(), 0x36.toByte(),
        0x32.toByte(), 0x30.toByte(), 0x37.toByte(), 0x32.toByte(), 0x37.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(),
        0x31.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x52.toByte(), 0xAA.toByte(), 0x0D.toByte()
    )

    @Test
    fun testModbusCrc() {
        val warningPayload = SAMPLE_WARNING_RESPONSE.copyOfRange(0, SAMPLE_WARNING_RESPONSE.size - 3)
        assertEquals(0x1F91, WattcycleProtocol.modbusCrc16(warningPayload))

        val analogPayload = SAMPLE_ANALOG_RESPONSE.copyOfRange(0, SAMPLE_ANALOG_RESPONSE.size - 3)
        assertEquals(0x4B22, WattcycleProtocol.modbusCrc16(analogPayload))

        val productPayload = SAMPLE_PRODUCT_RESPONSE.copyOfRange(0, SAMPLE_PRODUCT_RESPONSE.size - 3)
        assertEquals(0x52AA, WattcycleProtocol.modbusCrc16(productPayload))
    }

    @Test
    fun testBuildReadFrame() {
        val analogFrame = WattcycleProtocol.buildReadFrame(WattcycleProtocol.DP_ANALOG_QUANTITY, 0, WattcycleProtocol.FRAME_HEAD)
        val expectedAnalogHex = "7E000103008C000099420D"
        assertEquals(expectedAnalogHex, analogFrame.joinToString("") { "%02X".format(it) })
        assertTrue(WattcycleProtocol.verifyCrc(analogFrame))

        val altAnalogFrame = WattcycleProtocol.buildReadFrame(WattcycleProtocol.DP_ANALOG_QUANTITY, 0, WattcycleProtocol.FRAME_HEAD_ALT)
        val expectedAltAnalogHex = "1E000103008C0000B1440D"
        assertEquals(expectedAltAnalogHex, altAnalogFrame.joinToString("") { "%02X".format(it) })
        assertTrue(WattcycleProtocol.verifyCrc(altAnalogFrame))

        val prodFrame = WattcycleProtocol.buildReadFrame(WattcycleProtocol.DP_PRODUCT_INFO, 0, WattcycleProtocol.FRAME_HEAD)
        val expectedProdHex = "7E000103009200009F220D"
        assertEquals(expectedProdHex, prodFrame.joinToString("") { "%02X".format(it) })
        assertTrue(WattcycleProtocol.verifyCrc(prodFrame))
    }

    @Test
    fun testVerifyCrc() {
        assertTrue(WattcycleProtocol.verifyCrc(SAMPLE_WARNING_RESPONSE))
        assertTrue(WattcycleProtocol.verifyCrc(SAMPLE_ANALOG_RESPONSE))
        assertTrue(WattcycleProtocol.verifyCrc(SAMPLE_PRODUCT_RESPONSE))

        val corrupted = SAMPLE_WARNING_RESPONSE.copyOf()
        corrupted[10] = (corrupted[10].toInt() xor 0xFF).toByte()
        assertFalse(WattcycleProtocol.verifyCrc(corrupted))
    }

    @Test
    fun testParseAnalogQuantity() {
        val data = SAMPLE_ANALOG_RESPONSE.copyOfRange(8, SAMPLE_ANALOG_RESPONSE.size - 3)
        val parsed = WattcycleProtocol.parseAnalogQuantity(data)
        assertNotNull(parsed)
        parsed!!

        assertEquals(4, parsed.cellCount)
        assertEquals(4, parsed.cellVoltages.size)
        assertEquals(3.294, parsed.cellVoltages[0], 0.001)
        assertEquals(3.293, parsed.cellVoltages[1], 0.001)
        assertEquals(3.295, parsed.cellVoltages[2], 0.001)
        assertEquals(3.290, parsed.cellVoltages[3], 0.001)

        assertEquals(4, parsed.temperatureCount)
        assertEquals(18.7, parsed.mosTemperature, 0.1)
        assertEquals(19.8, parsed.pcbTemperature, 0.1)
        assertEquals(2, parsed.cellTemperatures.size)
        assertEquals(17.6, parsed.cellTemperatures[0], 0.1)
        assertEquals(17.6, parsed.cellTemperatures[1], 0.1)

        assertEquals(0.0, parsed.current, 0.01)
        assertEquals(13.17, parsed.moduleVoltage, 0.01)
        assertEquals(183.4, parsed.remainingCapacity, 0.1)
        assertEquals(314.0, parsed.totalCapacity, 0.1)
        assertEquals(5, parsed.cycleNumber)
        assertEquals(314.0, parsed.designCapacity, 0.1)
        assertEquals(58, parsed.soc)
    }

    @Test
    fun testParseProductInfo() {
        val data = SAMPLE_PRODUCT_RESPONSE.copyOfRange(8, SAMPLE_PRODUCT_RESPONSE.size - 3)
        val parsed = WattcycleProtocol.parseProductInfo(data)
        assertNotNull(parsed)
        parsed!!
        assertEquals("WT12_20004SW10_L447", parsed.firmwareVersion)
        assertEquals("60016016207270001", parsed.serialNumber)
    }
}
