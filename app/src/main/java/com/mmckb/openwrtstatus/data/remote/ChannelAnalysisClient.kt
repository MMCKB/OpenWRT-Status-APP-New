package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 信道分析数据层（LuCI admin/status/channel_analysis 的复刻）：
 * 复刻自 /luci-static/resources/view/status/channel_analysis.js 的数据源——
 * ubus `iwinfo info` / `iwinfo freqlist` / `iwinfo scan`（经 SSH 执行）。
 *
 * 一次连接采集全部 wlan 接口：
 *   echo __IF__:<dev>   → iwinfo info   （本机接口，画入图顶）
 *   echo __FREQ__:<dev> → iwinfo freqlist（各频段可用信道，决定页签与 x 轴）
 *   echo __SCAN__:<dev> → iwinfo scan   （邻近网络；触发扫描约需数秒）
 */
class ChannelAnalysisClient {

    /** 本机无线接口信息（iwinfo info）。 */
    data class LocalWifiInfo(
        val ifname: String,
        val ssid: String?,
        val bssid: String?,
        val channel: Int,
        val centerChan1: Int?,
        val centerChan2: Int?,
        val htmode: String?,
        val mode: String?,
        val signal: Int,
        val band: Int
    )

    /** 扫描到的邻近无线网络（宽度按 ht/vht/he operation 换算，同 LuCI 逻辑）。 */
    data class ChannelStation(
        val ssid: String?,
        val bssid: String,
        val channel: Int,
        val signal: Int,
        val quality: Int,
        val qualityMax: Int,
        val mode: String?,
        val band: Int,
        val widthMhz: Int,
        val widthText: String,
        val centerChannels: List<Int>
    )

    /** 一个接口的一个频段（每个频段一个页签）。 */
    data class ChannelBandData(
        val ifname: String,
        val band: Int,
        val channels: List<Int>,
        val channelMhz: Map<Int, Int>,
        val local: LocalWifiInfo?,
        val stations: List<ChannelStation>
    )

    data class ChannelAnalysisData(
        val bands: List<ChannelBandData>
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun requireSsh(ssh: SshConfig?): SshConfig =
        ssh ?: throw RouterException(
            "信道分析需要 SSH 访问。",
            "请在设备编辑页开启 SSH 后重试。"
        )

    /** 采集全部 wlan 接口的 info/freqlist/scan（一次连接；扫描约需数秒）。 */
    suspend fun load(ssh: SshConfig?): ChannelAnalysisData = withContext(Dispatchers.IO) {
        val s = requireSsh(ssh)
        val script = buildString {
            append("echo __DEVS__; ")
            append("ls /sys/class/net 2>/dev/null | grep -E '^wlan[0-9]+$'; ")
            append("for d in \\$(ls /sys/class/net 2>/dev/null | grep -E '^wlan[0-9]+$'); do ")
            append("echo \"__IF__:\\$d\"; ")
            append("ubus -S call iwinfo info \"{\\\"device\\\":\\\"\\$d\\\"}\" 2>/dev/null; ")
            append("echo \"__FREQ__:\\$d\"; ")
            append("ubus -S call iwinfo freqlist \"{\\\"device\\\":\\\"\\$d\\\"}\" 2>/dev/null; ")
            append("echo \"__SCAN__:\\$d\"; ")
            append("ubus -S call iwinfo scan \"{\\\"device\\\":\\\"\\$d\\\"}\" 2>/dev/null; ")
            append("done")
        }
        val out = SshExec.run(s, script, 90_000)
        parse(out)
    }

    /** 解析标记分段输出为按接口×频段分组的数据。 */
    internal fun parse(out: String): ChannelAnalysisData {
        val marker = Regex("__(DEVS|IF|FREQ|SCAN)__:(\\S+)")
        val marks = marker.findAll(out).toList()

        var devs = listOf<String>()
        val infos = mutableMapOf<String, JsonObject>()
        val freqs = mutableMapOf<String, JsonArray>()
        val scans = mutableMapOf<String, JsonArray>()

        for (i in marks.indices) {
            val type = marks[i].groupValues[1]
            val arg = marks[i].groupValues[2]
            val start = marks[i].range.last + 1
            val end = marks.getOrNull(i + 1)?.range?.first ?: out.length
            val body = out.substring(start, end).trim()
            when (type) {
                "DEVS" -> devs = body.lineSequence().filter { it.isNotBlank() }.map { it.trim() }.toList()
                "IF" -> infos[arg] = parseObj(body)
                "FREQ" -> freqs[arg] = parseArr(body)
                "SCAN" -> scans[arg] = parseArr(body)
            }
        }
        if (devs.isEmpty()) devs = (infos.keys + freqs.keys + scans.keys).distinct()

        val bands = mutableListOf<ChannelBandData>()
        for (dev in devs) {
            val freqArr = freqs[dev] ?: JsonArray(emptyList())
            val localObj = infos[dev]
            val scanArr = scans[dev] ?: JsonArray(emptyList())

            // freqlist → 各频段信道与频率表
            val bandChannels = linkedMapOf<Int, MutableList<Int>>()
            val channelMhz = mutableMapOf<Int, Int>()
            for (el in freqArr) {
                val o = el as? JsonObject ?: continue
                val ch = o.int("channel") ?: continue
                if (ch <= 0) continue
                val mhz = o.int("mhz") ?: 0
                val band = (o.int("band") ?: bandOfChannel(ch))
                channelMhz[ch] = if (mhz > 0) mhz else defaultMhz(ch, band)
                bandChannels.getOrPut(band) { mutableListOf() }.add(ch)
            }

            val local = localObj?.let { parseLocal(dev, it) }
            val stations = scanArr.mapNotNull { parseStation(it) }

            for ((band, channels) in bandChannels) {
                val sorted = channels.distinct().sorted()
                val localInBand = local?.takeIf { it.band == band }
                val inBand = stations.filter { it.band == band || bandOfChannel(it.channel) == band }
                    .filter { it.channel in channelMhz.keys }
                bands.add(
                    ChannelBandData(
                        ifname = dev,
                        band = band,
                        channels = sorted,
                        channelMhz = channelMhz,
                        local = localInBand,
                        stations = inBand.sortedWith(
                            compareBy({ it.channel }, { it.ssid ?: "" }, { it.bssid })
                        )
                    )
                )
            }
        }
        return ChannelAnalysisData(bands)
    }

    // ---- JSON helpers ----------------------------------------------------------

    private fun parseObj(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body).let { it as? JsonObject }
    }.getOrNull()

    private fun parseArr(body: String): JsonArray = runCatching {
        val el = json.parseToJsonElement(body)
        (el as? JsonObject)?.get("results") as? JsonArray
            ?: (el as? JsonArray)
            ?: JsonArray(emptyList())
    }.getOrDefault(JsonArray(emptyList()))

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    private fun parseLocal(dev: String, o: JsonObject): LocalWifiInfo? {
        val channel = o.int("channel") ?: return null
        val bssid = o.str("bssid")
        if (bssid.isNullOrBlank() || bssid == "00:00:00:00:00:00") return null
        return LocalWifiInfo(
            ifname = dev,
            ssid = o.str("ssid") ?: "本机",
            bssid = bssid,
            channel = channel,
            centerChan1 = o.int("center_chan1"),
            centerChan2 = o.int("center_chan2"),
            htmode = o.str("htmode"),
            mode = o.str("mode"),
            signal = o.int("signal") ?: -100,
            band = bandOfChannel(channel)
        )
    }

    private fun parseStation(el: kotlinx.serialization.json.JsonElement): ChannelStation? {
        val o = el as? JsonObject ?: return null
        val bssid = o.str("bssid") ?: return null
        if (bssid == "00:00:00:00:00:00") return null
        val channel = o.int("channel") ?: return null
        val band = o.int("band") ?: bandOfChannel(channel)

        // 信道宽度换算（复刻 LuCI channel_analysis.js 的判定顺序 he → vht → ht）
        var widthMhz = 20
        var widthText = "20 MHz"
        val centers = mutableListOf(channel)
        val he = o["he_operation"] as? JsonObject
        val heW = he?.int("channel_width") ?: 0
        if (heW > 20) {
            widthMhz = when (heW) {
                40 -> 40
                160 -> 160
                else -> 80
            }
            widthText = "$widthMhz MHz"
            he?.int("center_freq_1")?.let { if (it > 0) centers[0] = it }
            if (heW == 160) he?.int("center_freq_2")?.let { if (it > 0) centers.add(it) }
        } else {
            val vht = o["vht_operation"] as? JsonObject
            val vW = vht?.int("channel_width") ?: 0
            if (vW > 40) {
                widthMhz = 80
                widthText = "80 MHz"
                vht?.int("center_freq_1")?.let { if (it > 0) centers[0] = it }
                val cf2 = vht?.int("center_freq_2")
                if (vW == 160) {
                    widthMhz = 160
                    widthText = "160 MHz"
                    cf2?.let { if (it > 0) centers.add(it) }
                } else if (vW == 8080) {
                    widthText = "80+80 MHz"
                    cf2?.let { if (it > 0) centers.add(it) }
                }
            } else {
                val ht = o["ht_operation"] as? JsonObject
                when (ht?.str("secondary_channel_offset")) {
                    "below" -> {
                        widthMhz = 40; widthText = "40 MHz"
                        centers[0] = (centers[0] - 2).coerceAtLeast(1)
                    }
                    "above" -> {
                        widthMhz = 40; widthText = "40 MHz"
                        centers[0] = centers[0] + 2
                    }
                    else -> {
                        val htw = ht?.int("channel_width") ?: 0
                        if (htw == 2040) widthText = "20 MHz (40 MHz Intolerant)"
                    }
                }
            }
        }

        return ChannelStation(
            ssid = o.str("ssid"),
            bssid = bssid,
            channel = channel,
            signal = o.int("signal") ?: -100,
            quality = o.int("quality") ?: 0,
            qualityMax = o.int("quality_max") ?: 0,
            mode = o.str("mode"),
            band = band,
            widthMhz = widthMhz,
            widthText = widthText,
            centerChannels = centers
        )
    }

    companion object {
        /** 信道号推断频段（freqlist 缺 band 字段时兜底）。 */
        fun bandOfChannel(ch: Int): Int = when {
            ch <= 14 -> 2
            ch <= 196 -> 5
            else -> 6
        }

        /** 信道 → 标称中心频率 MHz（freqlist 缺失时兜底，5G 仅覆盖常见低段）。 */
        fun defaultMhz(ch: Int, band: Int): Int = when (band) {
            2 -> 2412 + (ch - 1) * 5
            5 -> 5180 + (ch - 36) * 5
            6 -> 5955 + (ch - 1) * 5
            else -> 0
        }
    }
}
