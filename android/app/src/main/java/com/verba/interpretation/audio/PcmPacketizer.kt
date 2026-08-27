package com.verba.interpretation.audio

class PcmPacketizer(
    private val packetBytes: Int = PACKET_BYTES,
    private val onPacket: (ByteArray) -> Unit,
) {
    private val pending = ByteArray(packetBytes)
    private var pendingSize = 0

    @Synchronized
    fun offer(source: ByteArray, length: Int = source.size) {
        require(length in 0..source.size)
        var offset = 0
        while (offset < length) {
            val count = minOf(packetBytes - pendingSize, length - offset)
            source.copyInto(pending, pendingSize, offset, offset + count)
            pendingSize += count
            offset += count
            if (pendingSize == packetBytes) {
                onPacket(pending.copyOf())
                pendingSize = 0
            }
        }
    }

    @Synchronized
    fun reset() {
        pendingSize = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_PACKET = 1_280
        const val PACKET_BYTES = SAMPLES_PER_PACKET * Short.SIZE_BYTES
        const val PACKET_DURATION_MS = 80
    }
}
