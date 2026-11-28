package org.buran.runtime.display

import android.net.LocalSocket
import android.content.Context
import android.os.ParcelFileDescriptor
import org.buran.runtime.buranDisplayRoot
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

internal object X11Connection {
    private val authName = "MIT-MAGIC-COOKIE-1".toByteArray()

    fun sharedTmp(packageName: String) = File("/data/data/$packageName/fs/tmp")

    fun sharedTmp(context: Context) = context.buranDisplayRoot.resolve("tmp")

    fun writeAuthority(file: File, cookie: ByteArray, display: String = "") {
        DataOutputStream(file.outputStream()).use { data ->
            fun field(bytes: ByteArray) {
                data.writeShort(bytes.size)
                data.write(bytes)
            }

            data.writeShort(0xffff) // Xauthority FamilyWild.
            field(byteArrayOf())
            field(display.toByteArray())
            field(authName)
            field(cookie)
        }
    }

    fun connect(path: File, cookie: ByteArray, onSetup: (ByteBuffer) -> Unit = {}): LocalSocket {
        val connection = LocalSocket()
        try {
            connection.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
            connection.soTimeout = 500
            val authLength = (authName.size + 3) and -4
            val setup = ByteBuffer.allocate(12 + authLength + cookie.size).order(LITTLE_ENDIAN)
            setup.put(0, 'l'.code.toByte())
            setup.putShort(2, 11)
            setup.putShort(6, authName.size.toShort())
            setup.putShort(8, cookie.size.toShort())
            setup.position(12)
            setup.put(authName)
            setup.position(12 + authLength)
            setup.put(cookie)
            connection.outputStream.write(setup.array())
            val input = DataInputStream(connection.inputStream)
            val accepted = ByteArray(8).also { input.readFully(it) }
            check(accepted[0].toInt() == 1) { "X11 authorization failed" }
            val extraWords = (accepted[6].toInt() and 255) or ((accepted[7].toInt() and 255) shl 8)
            val info = ByteArray(4 * extraWords).also { input.readFully(it) }
            onSetup(ByteBuffer.wrap(info).order(LITTLE_ENDIAN))
            return connection
        } catch (exception: Exception) {
            connection.close()
            throw exception
        }
    }
}

internal class X11Requests private constructor(
    private val connection: LocalSocket,
    val root: Int,
    private val resourceBase: Int,
    private val resourceMask: Int,
) : AutoCloseable {
    private val input = DataInputStream(connection.inputStream)
    private val output = connection.outputStream
    private var sequence = 0

    fun internAtom(name: String): Int {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        return checkNotNull(request(16, 8 + ((bytes.size + 3) and -4)) {
            putShort(bytes.size.toShort()).putShort(0).put(bytes)
        }).getInt(8)
    }

    fun property(window: Int, atom: Int, type: Int) = request(20, 24) { // GetProperty
        putInt(window).putInt(atom).putInt(type).putInt(0).putInt(256)
    }

    fun replaceProperty(window: Int, atom: Int, type: Int, value: ByteArray) {
        send(18, 24 + ((value.size + 3) and -4)) { // ChangeProperty, Replace
            putInt(window).putInt(atom).putInt(type)
            put(8).put(0).putShort(0).putInt(value.size).put(value)
        }
    }

    fun setRootCardinal(name: String, value: Int) {
        val atom = internAtom(name)
        send(18, 28) {
            putInt(root).putInt(atom).putInt(6) // CARDINAL
            put(32).put(0).putShort(0).putInt(1).putInt(value)
        }
        val reply = property(root, atom, 6)
        check(reply != null && reply.getInt(16) == 1 && reply.getInt(32) == value)
    }

    fun queryExtension(name: String): Int? {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        val reply = request(98, 8 + ((bytes.size + 3) and -4)) {
            putShort(bytes.size.toShort()).putShort(0).put(bytes)
        } ?: return null
        return reply.get(9).toInt().and(255).takeIf { reply.get(8).toInt() != 0 }
    }

    fun resourceId(index: Int) = resourceBase or
        ((index shl Integer.numberOfTrailingZeros(resourceMask)) and resourceMask)

    fun send(opcode: Int, size: Int, minorOpcode: Int = 0, payload: ByteBuffer.() -> Unit) {
        val request = ByteBuffer.allocate(size).order(LITTLE_ENDIAN)
        request.put(opcode.toByte()).put(minorOpcode.toByte()).putShort((size / 4).toShort()).payload()
        output.write(request.array())
        sequence = (sequence + 1) and 0xffff
    }

    fun request(opcode: Int, size: Int, payload: ByteBuffer.() -> Unit): ByteBuffer? {
        send(opcode, size, payload = payload)
        while (true) {
            val reply = readPacket()
            val kind = reply.get(0).toInt() and 0x7f
            if (kind > 1) continue
            val replySequence = reply.getShort(2).toInt() and 0xffff
            if (kind == 0) {
                if (replySequence == sequence && (reply.get(1).toInt() == 3 || reply.get(1).toInt() == 9)) return null
                error("Ошибка X11 ${reply.get(1)} (запрос ${reply.get(10).toInt() and 0xff})")
            }
            check(replySequence == sequence) { "Неверная последовательность ответа X11" }
            return reply
        }
    }

    fun duplicateDescriptor(): ParcelFileDescriptor = ParcelFileDescriptor.dup(connection.fileDescriptor)

    private fun readPacket(): ByteBuffer {
        val header = ByteArray(32).also { input.readFully(it) }
        val packet = ByteBuffer.wrap(header).order(LITTLE_ENDIAN)
        val kind = header[0].toInt() and 0x7f
        if (kind != 1 && kind != 35) return packet
        val words = packet.getInt(4)
        check(words in 0..65535) { "Неверная длина ответа X11" }
        if (words == 0) return packet
        val fullPacket = header.copyOf(32 + words * 4)
        input.readFully(fullPacket, 32, words * 4)
        return ByteBuffer.wrap(fullPacket).order(LITTLE_ENDIAN)
    }

    override fun close() = connection.close()

    companion object {
        fun open(path: File, cookie: ByteArray): X11Requests {
            var root = 0
            var resourceBase = 0
            var resourceMask = 0
            val connection = X11Connection.connect(path, cookie) { setup ->
                val vendorLength = setup.getShort(16).toInt() and 0xffff
                val formats = setup.get(21).toInt() and 0xff
                root = setup.getInt(32 + ((vendorLength + 3) and -4) + formats * 8)
                resourceBase = setup.getInt(4)
                resourceMask = setup.getInt(8)
            }
            return X11Requests(connection, root, resourceBase, resourceMask)
        }
    }
}
