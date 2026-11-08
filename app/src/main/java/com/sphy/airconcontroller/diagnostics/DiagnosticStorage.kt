package com.sphy.airconcontroller.diagnostics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

object DiagnosticStorage {
    private const val ENDPOINT = "https://aisenseapi.com/services/v1/storage"

    @Throws(IOException::class)
    fun upload(text: String): String {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val data = text.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(data.size)
            connection.outputStream.use { it.write(data) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Storage service returned HTTP $status")
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(4096)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    if (result.length + count > 65_536) throw IOException("Storage service response too large")
                    result.append(chars, 0, count)
                }
                result.toString()
            }
            val result = try {
                JSONObject(response).getString("storage_url")
            } catch (e: JSONException) {
                throw IOException("Storage service returned an invalid response", e)
            }
            val url = try {
                URL(result)
            } catch (e: IOException) {
                throw IOException("Storage service returned an invalid URL", e)
            }
            if (url.protocol != "https" || url.host != "aisenseapi.com" ||
                url.userInfo != null || url.port !in listOf(-1, 443) ||
                !url.path.startsWith("/services/v1/storage/") || url.query != null || url.ref != null
            ) throw IOException("Storage service returned an unexpected URL")
            return result
        } finally {
            connection.disconnect()
        }
    }
}
