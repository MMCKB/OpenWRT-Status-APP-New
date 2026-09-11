package com.mmckb.openwrtstatus.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Error carrying a short message plus an actionable hint for the UI.
 */
class RouterException(
    message: String,
    val hint: String? = null
) : IOException(message)

/**
 * Client for the modern OpenWrt **rpcd ubus JSON-RPC** endpoint (`/ubus`).
 *
 * OpenWrt 19.07+ removed the legacy `luci-rpc` CGI endpoints
 * (`/cgi-bin/luci/rpc/auth`, `/cgi-bin/luci/rpc/sys`). LuCI itself talks to rpcd over
 * `/ubus`, which is why the router opens fine in a browser while an app using the legacy
 * path fails. This client mirrors that protocol:
 *
 * ```
 * POST /ubus
 * {"jsonrpc":"2.0","id":1,"method":"call","params":[<session>,"system","info",{}]}
 * ```
 *
 * Login uses the null session id and `session.login`, which returns `ubus_rpc_session`.
 */
class UbusRpcClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** The "null" ubus session used before authenticating. */
    private val nullSession = "00000000000000000000000000000000"

    private val clients = mutableMapOf<Boolean, OkHttpClient>()

    /**
     * Builds `http(s)://host:port/ubus`.
     *
     * Accepts a bare host (`192.168.2.1`), a host with a scheme (`http://192.168.2.1`) or
     * with a path (`192.168.2.1/cgi-bin/luci`), and always normalises onto the ubus path
     * with the configured port. This is the single place where "address + port" is joined,
     * so a malformed combination cannot silently produce a broken URL.
     */
    fun buildEndpoint(host: String, port: Int, useHttps: Boolean): String {
        val scheme = if (useHttps) "https" else "http"
        var value = host.trim().trimEnd('/')
        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) value = value.substring(schemeIndex + 3)
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (value.isEmpty()) {
            throw RouterException("路由器地址为空。", "请在设置中填写路由器地址，例如 192.168.2.1。")
        }
        val safePort = port.coerceIn(1, 65535)
        return "$scheme://$value:$safePort/ubus"
    }

    private fun client(allowInsecureTls: Boolean): OkHttpClient = clients.getOrPut(allowInsecureTls) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
        if (allowInsecureTls) builder.trustAllCertificates()
        builder.build()
    }

    /**
     * Routers commonly serve LuCI over HTTPS with a self-signed certificate, which OkHttp
     * rejects. Opting in here is explicit and only used when the user enables it.
     */
    private fun OkHttpClient.Builder.trustAllCertificates() {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        sslSocketFactory(context.socketFactory, trustAll)
        hostnameVerifier { _, _ -> true }
    }

    /** Authenticates and returns the ubus session id. */
    suspend fun login(
        endpoint: String,
        username: String,
        password: String,
        allowInsecureTls: Boolean
    ): String = withContext(Dispatchers.IO) {
        val params = buildJsonObject {
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
        }
        val result = callInternal(endpoint, nullSession, "session", "login", params, allowInsecureTls)
        val sid = result
            .takeIf { it is JsonObject }
            ?.jsonObject
            ?.get("ubus_rpc_session")
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive
            ?.content
        if (sid.isNullOrBlank()) {
            throw RouterException(
                "未能创建路由器会话。",
                "请确认用户名具备 rpcd 登录权限（通常为 root），且密码正确。"
            )
        }
        sid
    }

    /** Invokes an ubus object method and returns the `result[1]` payload. */
    suspend fun call(
        endpoint: String,
        token: String,
        target: String,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        allowInsecureTls: Boolean = false
    ): JsonElement = withContext(Dispatchers.IO) {
        callInternal(endpoint, token, target, method, params, allowInsecureTls)
    }

    private fun callInternal(
        endpoint: String,
        token: String,
        target: String,
        method: String,
        params: JsonObject,
        allowInsecureTls: Boolean
    ): JsonElement {
        val bodyText = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(System.currentTimeMillis()))
            put("method", JsonPrimitive("call"))
            put("params", buildJsonArray {
                add(JsonPrimitive(token))
                add(JsonPrimitive(target))
                add(JsonPrimitive(method))
                add(params)
            })
        }.toString()

        val text = try {
            val request = Request.Builder()
                .url(endpoint)
                .post(bodyText.toRequestBody(mediaType))
                .build()
            client(allowInsecureTls).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response.code, endpoint)
                response.body?.string().orEmpty()
            }
        } catch (e: RouterException) {
            throw e
        } catch (e: UnknownHostException) {
            throw RouterException("无法解析路由器地址。", "请检查设置中的地址是否正确（$endpoint）。")
        } catch (e: ConnectException) {
            throw RouterException(
                "连接被拒绝或无法到达。",
                "请确认地址与端口（$endpoint），并确认手机与路由器处于同一局域网。"
            )
        } catch (e: SocketTimeoutException) {
            throw RouterException("连接超时，路由器无响应。", "请检查路由器是否开机、端口是否正确。")
        } catch (e: SSLException) {
            throw RouterException(
                "HTTPS 证书校验失败。",
                "路由器多使用自签名证书，可在设置中开启「忽略证书校验」。"
            )
        } catch (e: IOException) {
            throw RouterException("网络请求失败：${e.message}", "请检查网络后重试。")
        }

        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw RouterException(
                "路由器返回了无法识别的响应。",
                "该地址可能不是 rpcd/ubus 接口（ubus 路径应为 /ubus），请检查端口。"
            )
        }

        val result = root["result"]
        if (result !is JsonArray || result.size < 2) {
            val err = root["error"]?.takeIf { it !is JsonNull }?.toString()
            throw RouterException("路由器拒绝了请求。", err ?: "请检查用户名、密码与 rpcd 权限。")
        }

        val code = (result[0] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
        if (code != 0) {
            throw RouterException("ubus 调用 $target.$method 失败（代码 $code）。", ubusHint(code))
        }
        return result[1]
    }

    private fun httpError(code: Int, endpoint: String): RouterException = when (code) {
        401, 403 -> RouterException("认证失败（HTTP $code）。", "请检查用户名与密码。")
        404 -> RouterException(
            "接口不存在（HTTP 404）。",
            "请确认端口指向 LuCI/rpcd 服务（$endpoint），当前端口可能不是管理页面端口。"
        )
        else -> RouterException("路由器返回 HTTP $code。", "请检查地址、端口与路由器服务状态。")
    }

    private fun ubusHint(code: Int): String = when (code) {
        1 -> "无效命令（ubus 代码 1）。"
        2 -> "无效参数（ubus 代码 2）。"
        3 -> "方法不存在（ubus 代码 3）。"
        4 -> "对象不存在（ubus 代码 4）；可能缺少对应 rpcd 插件。"
        5 -> "无数据（ubus 代码 5）。"
        6 -> "权限不足（ubus 代码 6）；请使用具备权限的账号。"
        7 -> "调用超时（ubus 代码 7）。"
        else -> "请检查账号权限与 rpcd 配置。"
    }
}
