package com.example.android_niimprint.printing

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import kotlin.experimental.or

class NiimbotPacket(val type: Byte, val data: ByteArray) {

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