package com.verba.interpretation.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmPacketizerTest {
    @Test fun emitsStrict2560BytePacketsAcrossArbitraryReads() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = PcmPacketizer(onPacket = packets::add)
        val input = ByteArray(5_200) { (it % 127).toByte() }
        packetizer.offer(input.copyOfRange(0, 777))
        packetizer.offer(input.copyOfRange(777, input.size))
        assertEquals(2, packets.size)
        assertEquals(2_560, packets[0].size)
        assertEquals(1_280, PcmPacketizer.SAMPLES_PER_PACKET)
        assertEquals(80, PcmPacketizer.PACKET_DURATION_MS)
        assertArrayEquals(input.copyOfRange(0, 2_560), packets[0])
        assertArrayEquals(input.copyOfRange(2_560, 5_120), packets[1])
    }
}
