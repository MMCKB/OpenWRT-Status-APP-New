package com.mmckb.openwrtstatus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client for the legacy OpenWrt LuCI JSON-RPC interface
 * (the `luci-rpc` package exposing `/cgi-bin/luci/rpc/auth` and `/cgi-bin/luci/rpc/<module>`).
 *
 * See https://openwrt.org/docs/techref/ubus → the LuCI RPC is a wrapper around these calls.
 */
class LuciRpcClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Authenticate against LuCI and return a session token (sid).
     */
    suspend fun login(baseUrl: String, username: String, password: String): String =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/cgi-bin/luci/rpc/auth"
            val bodyText = buildJsonObject {
                put("id", JsonPrimitive(1))
                put("method", JsonPrimitive("login"))
                put("params", buildJsonArray {
                    add(JsonPrimitive(username))
                    add(JsonPrimitive(password))
                })
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(bodyText.toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("登录失败：HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(text).jsonObject
            val result = root["result"]
            if (result == null || result is JsonNull) {
                val err = root["error"]?.toString() ?: "未知错误"
                throw IOException("登录被拒绝：$err")
            }
            result.jsonPrimitive.content
        }

    /**
     * Invoke a LuCI RPC method. Returns the `result` element of the JSON-RPC response.
     */
    suspend fun call(
        baseUrl: String,
        token: String,
        module: String,
        method: String,
        params: List<Any> = emptyList()
    ): JsonElement = withContext(Dispatchers.IO) {
        val url = "$baseUrl/cgi-bin/luci/rpc/$module?auth=$token"
        val bodyText = buildJsonObject {
            put("id", JsonPrimitive(1))
            put("method", JsonPrimitive(method))
            put("params", buildJsonArray {
                params.forEach { add(JsonPrimitive(it.toString())) }
            })
            put("jsonrpc", JsonPrimitive("2.0"))
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(bodyText.toRequestBody(mediaType))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("RPC $method 失败：HTTP ${response.code}")
        }
        val text = response.body?.string().orEmpty()
        val root = json.parseToJsonElement(text).jsonObject
        val err = root["error"]
        if (err != null && err !is JsonNull) {
            throw IOException("RPC $method 错误：$err")
        }
        root["result"] ?: JsonNull
    }
}
