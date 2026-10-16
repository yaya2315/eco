package com.blackholecode.eco

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Escribe audio PCM de 16 bits mono como archivo .wav (lo que Whisper lee directo). */
object Wav {
    fun write(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataLen = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                    // tamaño del bloque fmt
            putShort(1.toShort())         // PCM
            putShort(1.toShort())         // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)        // bytes por segundo
            putShort(2.toShort())         // bytes por muestra
            putShort(16.toShort())        // bits por muestra
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen)
        }
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(pcm)
        FileOutputStream(file).use {
            it.write(header.array())
            it.write(body.array())
        }
    }
}
