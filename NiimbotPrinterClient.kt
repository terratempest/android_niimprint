import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.util.*
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import kotlin.experimental.or

class NiimbotPrinterClient(
    address: String,
    bluetoothAdapter: BluetoothAdapter
) {

    private var socket: BluetoothSocket? = null

    private enum class RequestCodeEnum(val value: Int) {
        GET_INFO(64),
        GET_RFID(26),
        HEARTBEAT(220),
        SET_LABEL_TYPE(35),
        SET_LABEL_DENSITY(33),
        START_PRINT(1),
        END_PRINT(243),
        START_PAGE_PRINT(3),
        END_PAGE_PRINT(227),
        ALLOW_PRINT_CLEAR(32),
        SET_DIMENSION(19),
        SET_QUANTITY(21),
        GET_PRINT_STATUS(163)
    }

    enum class InfoEnum(val value: Int) {
        DENSITY(1),
        PRINTSPEED(2),
        LABELTYPE(1),
        LANGUAGETYPE(6),
        AUTOSHUTDOWNTIME(7),
        DEVICETYPE(8),
        SOFTVERSION(9),
        BATTERY(10),
        DEVICESERIAL(11),
        HARDVERSION(12)
    }

    private class NiimbotPacket(val type: Byte, val data: ByteArray) {

        companion object {
            fun fromBytes(pkt: ByteArray): NiimbotPacket {
                require(pkt.sliceArray(0..1).contentEquals(byteArrayOf(0x55, 0x55))) { "Invalid start bytes" }
                require(pkt.sliceArray(pkt.size - 2 until pkt.size).contentEquals(byteArrayOf(0xaa.toByte(),
                    0xaa.toByte()
                ))) { "Invalid end bytes" }

                val type = pkt[2]
                val len = pkt[3]
                val data = pkt.sliceArray(4 until 4 + len)

                var checksum = type.toInt() xor len.toInt()
                for (i in data) {
                    checksum = checksum xor i.toInt()
                }

                require(checksum.toByte() == pkt[pkt.size - 3]) { "Invalid checksum" }

                return NiimbotPacket(type, data)
            }

            fun naiveEncoder(img: Bitmap): Sequence<NiimbotPacket> = sequence {

                val grayscaleBitmap = toGrayscale(img)
                val invertedBitmap = invertColors(grayscaleBitmap)
                val binaryBitmap = convertToBinary(invertedBitmap)
                val imgData = bitmapToByteArray(binaryBitmap)

                for (x in 0 until img.height) {
                    val lineData = imgData.sliceArray(x * 12 until (x + 1) * 12)

                    val counts = Array(3) { i ->
                        countBitsOfBytes(lineData.sliceArray(i * 4 until (i + 1) * 4))
                    }

                    val header = ByteBuffer.allocate(6)
                        .putShort(x.toShort())
                        .put(counts[0].toByte())
                        .put(counts[1].toByte())
                        .put(counts[2].toByte())
                        .put(1.toByte())
                        .array()

                    val pkt = NiimbotPacket(0x85.toByte(), header + lineData)
                    yield(pkt)
                }
            }


            private fun countBitsOfBytes(data: ByteArray): Int {
                return data.fold(0L) { acc, byte ->
                    acc.shl(8).or(byte.toLong() and 0xFF)
                }.countOneBits()
            }

            private fun invertColors(bitmap: Bitmap): Bitmap {
                val width = bitmap.width
                val height = bitmap.height
                val invertedBitmap = Bitmap.createBitmap(width, height, bitmap.config)

                for (x in 0 until width) {
                    for (y in 0 until height) {
                        val pixel = bitmap.getPixel(x, y)
                        val red = 255 - Color.red(pixel)
                        val green = 255 - Color.green(pixel)
                        val blue = 255 - Color.blue(pixel)
                        invertedBitmap.setPixel(x, y, Color.rgb(red, green, blue))
                    }
                }

                return invertedBitmap
            }

            private fun toGrayscale(bitmap: Bitmap): Bitmap {
                val width = bitmap.width
                val height = bitmap.height
                val grayscaleBitmap = Bitmap.createBitmap(width, height, bitmap.config)

                for (x in 0 until width) {
                    for (y in 0 until height) {
                        val pixel = bitmap.getPixel(x, y)
                        val avg = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                        grayscaleBitmap.setPixel(x, y, Color.rgb(avg, avg, avg))
                    }
                }

                return grayscaleBitmap
            }

            private fun convertToBinary(bitmap: Bitmap): Bitmap {
                val width = bitmap.width
                val height = bitmap.height
                val binaryBitmap = Bitmap.createBitmap(width, height, bitmap.config)

                for (x in 0 until width) {
                    for (y in 0 until height) {
                        val pixel = bitmap.getPixel(x, y)
                        val avg = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                        binaryBitmap.setPixel(x, y, if (avg < 128) Color.BLACK else Color.WHITE)
                    }
                }

                return binaryBitmap
            }

            private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
                val width = bitmap.width
                val height = bitmap.height
                val byteArraySize = (width * height + 7) / 8 // Round up division
                val result = ByteArray(byteArraySize)

                var bytePosition = 0
                var bitPosition = 0
                var currentByte: Byte = 0

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        val isWhite = Color.red(pixel) > 128 || Color.green(pixel) > 128 || Color.blue(pixel) > 128
                        if (isWhite) {
                            currentByte = currentByte or (1 shl (7 - bitPosition)).toByte()
                        }

                        bitPosition++
                        if (bitPosition == 8) {
                            result[bytePosition] = currentByte
                            bytePosition++
                            bitPosition = 0
                            currentByte = 0
                        }
                    }
                }

                // If there are remaining bits that haven't been written after looping through the pixels
                if (bitPosition != 0) {
                    result[bytePosition] = currentByte
                }

                return result
            }

        }

        fun toBytes(): ByteArray {
            var checksum = type.toInt() xor data.size
            for (i in data) {
                checksum = checksum xor i.toInt()
            }

            return byteArrayOf(0x55, 0x55, type) +
                    data.size.toByte() +
                    data +
                    checksum.toByte() +
                    byteArrayOf(0xaa.toByte(), 0xaa.toByte())
        }

        override fun toString(): String {
            return "<NiimbotPacket type=$type data=${data.contentToString()}>"
        }

    }

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

    private fun send(packet: NiimbotPacket) {
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
        Log.d("Print Status: ", "Print Status: Page: $page, progress1: $progress1, progress2: $progress2")
        return mapOf("page" to page, "progress1" to progress1, "progress2" to progress2)
    }

    private fun resizeBitmap(source: Bitmap, desiredWidth: Int, desiredHeight: Int): Bitmap {
        val scaleWidth = desiredWidth.toFloat() / source.width
        val scaleHeight = desiredHeight.toFloat() / source.height
        val matrix = Matrix()
        matrix.postScale(scaleWidth, scaleHeight)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    suspend fun printLabel(image: Bitmap, width: Int, height: Int, labelQty: Int = 1, labelType: Int = 1, labelDensity: Int = 2) = withContext(Dispatchers.IO) {

        val rotatedPreviewImage = if (image.width != width || image.height != height){
            rotateBitmap(resizeBitmap(image, width, height), 90f)
        } else {
            rotateBitmap(image, 90f)
        }

        setLabelType(labelType)

        setLabelDensity(labelDensity)
        startPrint()
        allowPrintClear()
        startPagePrint()
        setDimension(width, height)
        setQuantity(labelQty)

        // Convert the image to packets and send to the printer
        val packets = NiimbotPacket.naiveEncoder(rotatedPreviewImage)
        for (pkt in packets) {
            send(pkt)
        }

        endPagePrint()
        while (getPrintStatus()["page"] != labelQty) {
            Thread.sleep(100)
        }

        endPrint()

    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

}