package app.scalemate.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

open class UpdateTransport {
    open fun getText(url: String, maxBytes: Int = 2 * 1024 * 1024): String {
        httpsUrl(url, "request")
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateFailure(
                if (code == 404) UpdateFailure.Kind.NOT_FOUND else UpdateFailure.Kind.NETWORK,
                "HTTP $code"
            )
            val length = connection.contentLengthLong
            if (length > maxBytes) throw UpdateFailure(UpdateFailure.Kind.INVALID, "Response too large")
            val bytes = connection.inputStream.use { it.readLimited(maxBytes) }
            return bytes.toString(Charsets.UTF_8)
        } catch (error: UpdateFailure) {
            throw error
        } catch (error: Exception) {
            throw UpdateFailure(UpdateFailure.Kind.NETWORK, error.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }

    fun downloadTo(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Long {
        httpsUrl(url, "download")
        destination.parentFile?.mkdirs()
        destination.delete()
        val connection = open(url, download = true)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateFailure(
                if (code == 404) UpdateFailure.Kind.NOT_FOUND else UpdateFailure.Kind.NETWORK,
                "HTTP $code"
            )
            val total = connection.contentLengthLong
            var downloaded = 0L
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Download cancelled")
                        if ((System.nanoTime() - started) / 1_000_000 > 180_000) {
                            throw UpdateFailure(UpdateFailure.Kind.NETWORK, "Download route timed out")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                        if (downloaded > MAX_APK_BYTES) {
                            throw UpdateFailure(UpdateFailure.Kind.INVALID, "APK is too large")
                        }
                    }
                }
            }
            if (downloaded <= 0) throw UpdateFailure(UpdateFailure.Kind.INVALID, "Empty APK")
            return downloaded
        } catch (error: UpdateFailure) {
            destination.delete()
            throw error
        } catch (error: InterruptedException) {
            destination.delete()
            throw error
        } catch (error: Exception) {
            destination.delete()
            throw UpdateFailure(UpdateFailure.Kind.NETWORK, error.message ?: "Download failed")
        } finally {
            connection.disconnect()
        }
    }

    open fun downloadBytes(url: String, maxBytes: Int): ByteArray {
        httpsUrl(url, "download")
        val connection = open(url, download = true)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw UpdateFailure(
                if (code == 404) UpdateFailure.Kind.NOT_FOUND else UpdateFailure.Kind.NETWORK,
                "HTTP $code"
            )
            return connection.inputStream.use { it.readLimited(maxBytes) }
        } catch (error: UpdateFailure) {
            throw error
        } catch (error: Exception) {
            throw UpdateFailure(UpdateFailure.Kind.NETWORK, error.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, download: Boolean = false): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = if (download) 8_000 else 12_000
        // A download route must produce bytes promptly. The existing 35 s
        // first-byte wait made one stalled proxy look like a frozen update.
        connection.readTimeout = if (download) 10_000 else 18_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json, application/octet-stream, text/plain")
        connection.setRequestProperty("User-Agent", "ScaleMate-Android-Updater/1")
        return connection
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw UpdateFailure(UpdateFailure.Kind.INVALID, "Response too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_APK_BYTES = 120L * 1024 * 1024
    }
}
