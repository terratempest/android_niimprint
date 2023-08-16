package com.example.android_niimprint.printing

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.util.*
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream

class PrinterClient(
    address: String,
    bluetoothAdapter: BluetoothAdapter
) {

    private var socket: BluetoothSocket? = null

    init {

        initializeSocket(bluetoothAdapter, address)
    }


    @SuppressLint("MissingPermission")
    private fun initializeSocket(bluetoothAdapter: BluetoothAdapter, address: String) {
        try {
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)
            socket = device.createRfcommSocketToServiceRecord(bluetoothAdapter.getRemoteDevice(address).uuids[0].uuid)
            socket?.connect()
        } catch (e: IOException) {
            // Handle the error, maybe report to callback
            socket?.close()
        }
    }

    private fun recv(): List<NiimbotPacket> {
        val inputStream: InputStream = socket!!.inputStream
        val packets = mutableListOf<NiimbotPacket>()

        val startBytes = byteArrayOf(0x55.toByte(), 0x55.toByte())
        val endBytes = byteArrayOf(0xaa.toByte(), 0xaa.toByte())

        val buffer = ByteArray(1024)
        var bufferPosition = 0
        var bytesRead = 0

        fun refillBuffer() {
            bytesRead = inputStream.read(buffer)
            bufferPosition = 0
        }

        fun readByteFromBuffer(): Byte {
            if (bufferPosition >= bytesRead && bytesRead == buffer.size) {
                refillBuffer()
            }
            return buffer[bufferPosition++]
        }

        fun readBytesUntilFound(target: ByteArray): Boolean {
            val localBuffer = ByteArray(target.size)
            while (bufferPosition < bytesRead || bytesRead == buffer.size) {
                System.arraycopy(localBuffer, 1, localBuffer, 0, target.size - 1)
                localBuffer[target.size - 1] = readByteFromBuffer()
                if (localBuffer.contentEquals(target)) {
                    return true
                }
            }
            return false
        }

        fun extractPacketFromBuffer(): ByteArray? {
            val localBuffer = mutableListOf<Byte>()

            // Add start bytes first
            localBuffer.addAll(startBytes.toList())

            // Read the packet until end bytes or end of buffer
            while (true) {
                val byte = readByteFromBuffer()
                localBuffer.add(byte)

                if (localBuffer.size >= 4 && localBuffer.takeLast(2) == endBytes.toList()) {
                    break
                }

                if (bufferPosition >= bytesRead) {
                    return null
                }
            }

            return localBuffer.toByteArray()
        }

        while (true) {
            refillBuffer()
            if (bytesRead == -1) break

            while (readBytesUntilFound(startBytes)) {
                val packetBytes = extractPacketFromBuffer() ?: continue

                packets.add(NiimbotPacket.fromBytes(packetBytes))
            }
            if (bufferPosition >= bytesRead && bytesRead < buffer.size) break
        }

        return packets
    }

    fun send(packet: NiimbotPacket) {
        socket!!.outputStream.write(packet.toBytes())
    }

    private suspend fun transceive(reqCode: RequestCodeEnum, data: ByteArray, respOffset: Int = 1): NiimbotPacket? {
        val respCode = respOffset + reqCode.value
        send(NiimbotPacket(reqCode.value.toByte(), data))
        repeat(6) {
            for (packet in recv()) {
                when (packet.type.toUByte().toInt()) { // Using toUByte as converting from byte directly to int produces dumb numbers
                    219 -> throw IllegalArgumentException()
                    0 -> throw NotImplementedError()
                    respCode -> return packet
                }
            }
            delay(200L)
        }
        return null
    }

    suspend fun getInfo(key: InfoEnum): Any? {
        val packet = transceive(RequestCodeEnum.GET_INFO, byteArrayOf(key.value.toByte()), key.value)
        packet?.let {
            return when (key) {
                InfoEnum.DEVICESERIAL -> packet.data.joinToString("") { "%02x".format(it) }
                InfoEnum.SOFTVERSION -> packetToInt(packet) / 100.0
                InfoEnum.HARDVERSION -> packetToInt(packet) / 100.0
                else -> packetToInt(packet)
            }
        }
        return null
    }

    private fun packetToInt(packet: NiimbotPacket): Int {
        return packet.data.fold(0) { total, byte -> (total shl 8) + byte.toInt() }
    }
    suspend fun getRfid(): Map<String, Any?>? {
        val packet = transceive(RequestCodeEnum.GET_RFID, byteArrayOf(0x01))
        val data = packet!!.data

        if (data[0].toInt() == 0) {
            return null
        }

        val uuid = data.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }
        var idx = 8

        val barcodeLen = data[idx].toInt()
        idx++
        val barcode = data.sliceArray(idx until idx + barcodeLen).toString(Charsets.UTF_8)
        idx += barcodeLen

        val serialLen = data[idx].toInt()
        idx++
        val serial = data.sliceArray(idx until idx + serialLen).toString(Charsets.UTF_8)
        idx += serialLen

        val totalLen = (data[idx].toInt() shl 8) + data[idx + 1].toInt()
        val usedLen = (data[idx + 2].toInt() shl 8) + data[idx + 3].toInt()
        val type = data[idx + 4].toInt()

        return mapOf(
            "uuid" to uuid,
            "barcode" to barcode,
            "serial" to serial,
            "used_len" to usedLen,
            "total_len" to totalLen,
            "type" to type
        )
    }
    suspend fun heartbeat(): Map<String, Int?> {
        val packet = transceive(RequestCodeEnum.HEARTBEAT, byteArrayOf(0x01))
        val data = packet!!.data

        var closingState: Int? = null
        var powerLevel: Int? = null
        var paperState: Int? = null
        var rfidReadState: Int? = null

        when (data.size) {
            20 -> {
                paperState = data[18].toInt()
                rfidReadState = data[19].toInt()
            }
            13 -> {
                closingState = data[9].toInt()
                powerLevel = data[10].toInt()
                paperState = data[11].toInt()
                rfidReadState = data[12].toInt()
            }
            19 -> {
                closingState = data[15].toInt()
                powerLevel = data[16].toInt()
                paperState = data[17].toInt()
                rfidReadState = data[18].toInt()
            }
            10 -> {
                closingState = data[8].toInt()
                powerLevel = data[9].toInt()
                rfidReadState = data[8].toInt()
            }
            9 -> {
                closingState = data[8].toInt()
            }
        }

        return mapOf(
            "closingstate" to closingState,
            "powerlevel" to powerLevel,
            "paperstate" to paperState,
            "rfidreadstate" to rfidReadState
        )
    }

    suspend fun setLabelType(n: Int): Boolean {
        require(n in 1..3)
        val packet = transceive(RequestCodeEnum.SET_LABEL_TYPE, byteArrayOf(n.toByte()), 16)
        return packet!!.data[0].toInt() != 0
    }

    suspend fun setLabelDensity(n: Int): Boolean {
        require(n in 1..3)
        val packet = transceive(RequestCodeEnum.SET_LABEL_DENSITY, byteArrayOf(n.toByte()), 16)
        return packet!!.data[0].toInt() != 0
    }

    suspend fun startPrint(): Boolean {
        val packet = transceive(RequestCodeEnum.START_PRINT, byteArrayOf(0x01))
        return packet!!.data[0].toInt() != 0
    }

    suspend fun endPrint(): Boolean {
        val packet = transceive(RequestCodeEnum.END_PRINT, byteArrayOf(0x01))
        return packet!!.data[0].toInt() != 0
    }

    suspend fun startPagePrint(): Boolean {
        val packet = transceive(RequestCodeEnum.START_PAGE_PRINT, byteArrayOf(0x01))
        return packet!!.data[0].toInt() != 0
    }

    suspend fun endPagePrint(): Boolean {
        val packet = transceive(RequestCodeEnum.END_PAGE_PRINT, byteArrayOf(0x01))
        return packet!!.data[0].toInt() != 0
    }

    suspend fun allowPrintClear(): Boolean {
        val packet = transceive(RequestCodeEnum.ALLOW_PRINT_CLEAR, byteArrayOf(0x01), 16)
        return packet!!.data[0].toInt() != 0
    }

    suspend fun setDimension(w: Int, h: Int): Boolean {
        val buffer = ByteBuffer.allocate(4).putShort(w.toShort()).putShort(h.toShort()).array()
        val packet = transceive(RequestCodeEnum.SET_DIMENSION, buffer)
        return packet!!.data[0].toInt() != 0
    }

    suspend fun setQuantity(n: Int): Boolean {
        val buffer = ByteBuffer.allocate(2).putShort(n.toShort()).array()
        val packet = transceive(RequestCodeEnum.SET_QUANTITY, buffer)
        return packet!!.data[0].toInt() != 0
    }

    suspend fun getPrintStatus(): Map<String, Int> {
        val packet = transceive(RequestCodeEnum.GET_PRINT_STATUS, byteArrayOf(0x01), 16)
        val buffer = ByteBuffer.wrap(packet!!.data)
        val page = buffer.short.toInt()
        val progress1 = buffer.get().toInt()
        val progress2 = buffer.get().toInt()
        return mapOf("page" to page, "progress1" to progress1, "progress2" to progress2)
    }


}