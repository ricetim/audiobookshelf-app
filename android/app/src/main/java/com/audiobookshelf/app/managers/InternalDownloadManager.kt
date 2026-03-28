package com.audiobookshelf.app.managers

import android.util.Log
import java.io.*
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ConnectionPool

/**
 * Manages the internal download process.
 *
 * @property outputStream The output stream to write the downloaded data.
 * @property progressCallback The callback to report download progress.
 */
class InternalDownloadManager(
        private val outputStream: FileOutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  private val tag = "InternalDownloadManager"
  private val writer = BinaryFileWriter(outputStream, progressCallback)

  companion object {
    // Shared across all downloads so TCP connections are reused between files.
    // Pool size matches maxSimultaneousDownloads in DownloadItemManager.
    val client: OkHttpClient =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS) // no timeout — large audio files can take minutes
                    .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                    .build()
  }

  /**
   * Downloads a file from the given URL.
   *
   * @param url The URL to download the file from.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun download(url: String) {
    val request: Request = Request.Builder().url(url).addHeader("Accept-Encoding", "identity").build()
    client.newCall(request)
            .enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        Log.e(tag, "Download URL $url FAILED", e)
                        progressCallback.onComplete(true)
                      }

                      override fun onResponse(call: Call, response: Response) {
                        val body = response.body
                        if (body == null) {
                          Log.e(tag, "Response doesn't contain a file")
                          progressCallback.onComplete(true)
                          return
                        }
                        body.use { responseBody ->
                          if (!response.isSuccessful) {
                            Log.e(tag, "Download URL $url failed with HTTP ${response.code}")
                            progressCallback.onComplete(true)
                            return
                          }
                          val length: Long = response.header("Content-Length")?.toLongOrNull() ?: 0L
                          writer.write(responseBody.byteStream(), length)
                        }
                      }
                    }
            )
  }

  /**
   * Closes the download manager and releases resources.
   *
   * @throws Exception If an error occurs during closing.
   */
  @Throws(Exception::class)
  override fun close() {
    writer.close()
  }
}

/**
 * Writes binary data to an output stream.
 *
 * @property outputStream The output stream to write the data to.
 * @property progressCallback The callback to report write progress.
 */
class BinaryFileWriter(
        private val outputStream: OutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  /**
   * Writes data from the input stream to the output stream.
   *
   * @param inputStream The input stream to read the data from.
   * @param length The total length of the data to be written.
   * @return The total number of bytes written.
   * @throws IOException If an I/O error occurs.
   */
  fun write(inputStream: InputStream, length: Long): Long {
    val dataBuffer = ByteArray(CHUNK_SIZE)
    var totalBytes: Long = 0
    var readBytes: Int
    var lastProgressBytes: Long = 0
    try {
      while (inputStream.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        if (totalBytes - lastProgressBytes >= PROGRESS_INTERVAL_BYTES) {
          progressCallback.onProgress(totalBytes, if (length > 0) (totalBytes * 100L) / length else 0L)
          lastProgressBytes = totalBytes
        }
      }
      progressCallback.onProgress(totalBytes, if (length > 0) (totalBytes * 100L) / length else 100L)
      progressCallback.onComplete(false)
    } catch (e: IOException) {
      Log.e("BinaryFileWriter", "IO error during download write after $totalBytes bytes", e)
      progressCallback.onComplete(true)
    } finally {
      try { outputStream.close() } catch (e: IOException) { Log.w("BinaryFileWriter", "Failed to close output stream", e) }
    }
    return totalBytes
  }

  /**
   * Closes the writer and releases resources.
   *
   * @throws IOException If an error occurs during closing.
   */
  @Throws(IOException::class)
  override fun close() {
    outputStream.close()
  }

  companion object {
    private const val CHUNK_SIZE = 262144 // 256 KiB — matches typical SFTP/SCP frame size
    private const val PROGRESS_INTERVAL_BYTES = 2 * 1024 * 1024 // report progress every 2 MiB
  }
}
