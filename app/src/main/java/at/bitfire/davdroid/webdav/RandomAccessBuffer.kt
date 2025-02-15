package at.bitfire.davdroid.webdav

import android.content.Context
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.davdroid.log.Logger
import okhttp3.HttpUrl
import org.apache.commons.io.FileUtils
import kotlin.math.min

class RandomAccessBuffer(
    context: Context,
    private val url: HttpUrl,
    private val fileLength: Long,
    private val documentState: DocumentState?,
    private val reader: Reader
) {

    companion object {
        /** one GET request per 2 MB */
        const val PAGE_SIZE: Int = (2*FileUtils.ONE_MB).toInt()
    }

    val pageCache by lazy { PageCache.getInstance(context) }


    fun read(offset: Long, size: Int, dst: ByteArray): Int {
        val lastPos = min(offset + size, fileLength)

        var position = offset
        var transferred = 0
        while (position < lastPos) {
            // calculate page and offset to start with
            val pageIdx = position / PAGE_SIZE
            val pageOffset = position.mod(PAGE_SIZE)

            Logger.log.fine("Reading page $pageIdx (offset=$pageOffset, transferred=$transferred)")
            val pageData = getPage(pageIdx, pageOffset.toLong(), min(
                PAGE_SIZE - pageOffset,         // read either until the possible end of the page ...
                size - transferred              // .. or until the requested number of bytes is available
            ))

            val nrBytes = min(
                pageData.size,                     // copy either until the end of the page ...
                size - transferred              // ... or until the requested number of bytes has been copied
            )
            Logger.log.fine("Transferring $nrBytes bytes from page $pageIdx (offset $pageOffset) to destination (offset $transferred)")
            System.arraycopy(pageData, 0, dst, transferred, nrBytes)
            transferred += nrBytes
            position += nrBytes

            Logger.log.fine("Now $transferred of $size bytes are transferred")
        }

        return transferred
    }

    private fun getPage(pageIdx: Long, offset: Long, size: Int): ByteArray {
        if (documentState != null) {
            // we can only use the cache if we have ETag/Last-Modified

            val key = PageCache.Key(url, documentState, pageIdx)
            return pageCache.get(key, offset, size) {
                getPageDirect(pageIdx)
            }!!
        }

        // caching not possible because of missing ETag/Last-Modified
        return getPageDirect(pageIdx, offset, size)
    }

    internal fun getPageDirect(pageIdx: Long, offset: Long = 0, size: Int = PAGE_SIZE): ByteArray {
        val startPos = pageIdx * PAGE_SIZE + offset
        if (startPos >= fileLength)
            throw IndexOutOfBoundsException("Can't get page at or after EOF ($startPos >= $fileLength)")

        val idxLast = min(startPos + size, fileLength)

        val resultSize = (idxLast - startPos).toInt()
        val buffer = ByteArray(resultSize)
        if (reader.readDirect(startPos, resultSize, buffer) != resultSize)
            throw DavException("Couldn't read $resultSize bytes from position $startPos (file size = $fileLength)")
        return buffer
    }


    interface Reader {
        fun readDirect(offset: Long, size: Int, dst: ByteArray): Int
    }

}