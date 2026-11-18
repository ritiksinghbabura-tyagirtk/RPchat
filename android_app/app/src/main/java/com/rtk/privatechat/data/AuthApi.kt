package com.rtk.privatechat.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AuthApi {
    // Local development: Android app and Ubuntu PRoot on the same phone.
    // For another device, replace this with your server's private LAN address.
    private const val BASE_URL = "http://127.0.0.1:8789"

    fun post(
        path: String,
        body: JSONObject,
        token: String? = null
    ): JSONObject {
        return request("POST", path, body, token)
    }

    fun get(path: String, token: String): JSONObject {
        return request("GET", path, null, token)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?
    ): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection()
            as HttpURLConnection)

        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8"
                )
            }

            if (!token.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization", "Bearer $token"
                )
            }

            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.bufferedReader()?.use {
                it.readText()
            } ?: "{}"

            val response = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                JSONObject()
            }

            if (status !in 200..299) {
                throw IOException(
                    response.optString("error", "Server error: $status")
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }
}
