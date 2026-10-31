package com.fadysaeed.pdfx.engine

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lets MuPDF read a document straight from a file descriptor, so a 500 MB PDF shared from
 * another app is never copied into memory or onto disk just to be opened.
 */
class FdStream(private val pfd: ParcelFileDescriptor) : SeekableInputStream, Closeable {
    private val input = FileInputStream(pfd.fileDescriptor)
    private val channel: FileChannel = input.channel
    private var pos = 0L

    val size: Long get() = channel.size()

    override fun read(b: ByteArray): Int {
        if (b.isEmpty()) return 0
        val n = channel.read(ByteBuffer.wrap(b), pos)
        if (n <= 0) return -1
        pos += n
        return n
    }

    override fun seek(offset: Long, whence: Int): Long {
        pos = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> pos + offset
            SeekableStream.SEEK_END -> channel.size() + offset
            else -> pos
        }.coerceAtLeast(0L)
        return pos
    }

    override fun position(): Long = pos

    override fun close() {
        runCatching { channel.close() }
        runCatching { input.close() }
        runCatching { pfd.close() }
    }
}
