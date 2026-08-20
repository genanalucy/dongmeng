package com.verba.interpretation.audio

enum class PlaybackRoute { LEFT, RIGHT, BOTH, CAPTIONS }

object StereoRouter {
    fun routeMonoPcm16(mono: ByteArray, route: PlaybackRoute): ByteArray {
        require(mono.size % Short.SIZE_BYTES == 0) { "PCM16 payload must have an even byte count" }
        if (route == PlaybackRoute.CAPTIONS) return ByteArray(0)
        val stereo = ByteArray(mono.size * 2)
        var input = 0
        var output = 0
        while (input < mono.size) {
            val low = mono[input]
            val high = mono[input + 1]
            if (route == PlaybackRoute.LEFT || route == PlaybackRoute.BOTH) {
                stereo[output] = low
                stereo[output + 1] = high
            }
            if (route == PlaybackRoute.RIGHT || route == PlaybackRoute.BOTH) {
                stereo[output + 2] = low
                stereo[output + 3] = high
            }
            input += 2
            output += 4
        }
        return stereo
    }
}
