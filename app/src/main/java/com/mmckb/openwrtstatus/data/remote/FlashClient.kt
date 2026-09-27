package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设备刷写能力信息（/proc/mtd、/proc/mounts、platform.sh）。 */
data class FlashInfo(
    val hasPlatformScript: Boolean,
    val hasRootfsData: Boolean,
    val mtdBlocks: List<Pair<String, String>> // (dev, name)
)

/** 固件校验结果（sysupgrade --test + md5/sha256/大小）。 */
data class FirmwareCheck(
    val valid: Boolean,
    val size: Long,
    val md5: String,
    val sha256: String,
    val output: String
)

/**
 * 备份与更新数据层（LuCI admin/system/flash 的完整复刻）：
 *  - 生成备份：`sysupgrade -b /tmp/backup.tar.gz` 后经 SFTP-less cat 下载；
 *  - 恢复配置：上传 /tmp/backup.tar.gz → `tar -tzf` 校验 →
 *    `sysupgrade --restore-backup` → 重启；
 *  - 出厂重置：`firstboot -r -y`（仅 squashfs 固件，hasRootfsData 判定）；
 *  - 固件刷写：上传 /tmp/firmware.bin → `sysupgrade --test` 校验 →
 *    `sysupgrade -v [-n] [--force]`（后台执行，设备随即重启）；
 *  - 配置页签：/etc/sysupgrade.conf 的读取与写回。
 *  全部操作需要设备开启 SSH。
 */
class FlashClient {

    /** 确认设备信息读取需要 SSH。 */
    private fun requireSsh(ssh: SshConfig?): SshConfig =
        ssh ?: throw RouterException(
            "备份与更新需要 SSH 访问。",
            "请在设备编辑页开启 SSH 后重试。"
        )

    suspend fun loadInfo(config: RouterConfig, ssh: SshConfig?): FlashInfo = withContext(Dispatchers.IO) {
        val s = requireSsh(ssh)
        val script = listOf(
            "echo __PLAT__", "test -e /lib/upgrade/platform.sh && echo yes || echo no",
            "echo __MTD__", "cat /proc/mtd 2>/dev/null",
            "echo __MNT__", "cat /proc/mounts 2>/dev/null"
        ).joinToString("; ")
        val out = SshExec.run(ssh, script, 20_000)
        val parts = out.split(Regex("__(?:PLAT|MTD|MNT)__"))
        val plat = parts.getOrNull(1)?.trim() ?: "no"
        val mtdRaw = parts.getOrNull(2)?.trim().orEmpty()
        val mounts = parts.getOrNull(3)?.trim().orEmpty()
        val mtdBlocks = mtdRaw.lineSequence().mapNotNull { line ->
            val m = Regex("^(mtd\\d+):\\s+\\S+\\s+\"([^\"]+)\"$").find(line.trim()) ?: return@mapNotNull null
            m.groupValues[1] to m.groupValues[2]
        }.filter { it.second != "u-boot" }
        FlashInfo(
            hasPlatformScript = plat == "yes",
            hasRootfsData = mtdRaw.contains("\"rootfs_data\"") ||
                mounts.contains("overlayfs:/overlay / "),
            mtdBlocks = mtdBlocks
        )
    }

    /** 生成配置备份 tar.gz（sysupgrade -b），返回字节流供下载。 */
    suspend fun generateBackup(config: RouterConfig, ssh: SshConfig?): ByteArray =
        withContext(Dispatchers.IO) {
            val tmp = "/tmp/backup-${System.currentTimeMillis()}.tar.gz"
            SshExec.run(
                ssh, "sysupgrade -b $tmp >/dev/null 2>&1; echo __RC__:$?", 60_000
            )
            val data = SshFiles.download(requireSsh(ssh), tmp)
            runCatching { SshExec.run(ssh, "rm -f $tmp", 10_000) }
            if (data.size < 2 || data[0] != 0x1f.toByte() || data[1] != 0x8b.toByte()) {
                throw RouterException("生成备份失败。", "请确认设备支持 sysupgrade 并重试。")
            }
            data
        }

    /** 出厂重置（擦除配置分区并自动重启）。 */
    suspend fun performReset(config: RouterConfig, ssh: SshConfig?) = withContext(Dispatchers.IO) {
        runCatching { SshExec.run(ssh, "firstboot -r -y", 30_000) }
        Unit
    }

    /** 校验已上传的备份存档可读（tar -tzf）。 */
    suspend fun verifyRestoreArchive(config: RouterConfig, ssh: SshConfig?): Boolean =
        withContext(Dispatchers.IO) {
            SshExec.run(
                ssh,
                "tar -tzf /tmp/backup.tar.gz >/dev/null 2>&1 && echo __OK__ || echo __FAIL__",
                20_000
            ).contains("__OK__")
        }

    /** 恢复配置并重启（sysupgrade --restore-backup + reboot）。 */
    suspend fun restoreBackup(config: RouterConfig, ssh: SshConfig?) = withContext(Dispatchers.IO) {
        runCatching {
            SshExec.run(
                ssh,
                "sysupgrade --restore-backup /tmp/backup.tar.gz >/dev/null 2>&1; " +
                    "echo __RESTORED__; sleep 1; reboot",
                60_000
            )
        }
        Unit
    }

    /** 固件校验（sysupgrade --test + md5/sha256/大小）。 */
    suspend fun testFirmware(config: RouterConfig, ssh: SshConfig?): FirmwareCheck =
        withContext(Dispatchers.IO) {
            val script = listOf(
                "echo __V__",
                "sysupgrade --test /tmp/firmware.bin >/dev/null 2>&1 && echo yes || echo no",
                "echo __SZ__", "wc -c < /tmp/firmware.bin",
                "echo __MD5__", "md5sum /tmp/firmware.bin 2>/dev/null | awk '{print \$1}'",
                "echo __SHA__", "sha256sum /tmp/firmware.bin 2>/dev/null | awk '{print \$1}'",
                "echo __OUT__", "sysupgrade --test /tmp/firmware.bin 2>&1"
            ).joinToString("; ")
            val out = SshExec.run(ssh, script, 60_000)
            val parts = out.split(Regex("__(?:V|SZ|MD5|SHA|OUT)__"))
            fun sec(i: Int): String = parts.getOrNull(i + 1)?.trim().orEmpty()
            FirmwareCheck(
                valid = sec(0) == "yes",
                size = sec(1).toLongOrNull() ?: 0,
                md5 = sec(2),
                sha256 = sec(3),
                output = sec(4)
            )
        }

    /**
     * 开始刷写固件（后台执行，设备随即重启，SSH 连接会中断属正常现象）。
     * keepSettings = false 时追加 -n（不保留配置）；force = true 时追加 --force。
     */
    suspend fun flashFirmware(
        config: RouterConfig,
        ssh: SshConfig,
        keepSettings: Boolean,
        force: Boolean
    ) = withContext(Dispatchers.IO) {
        val args = buildString {
            append("sysupgrade -v")
            if (!keepSettings) append(" -n")
            if (force) append(" --force")
            append(" /tmp/firmware.bin")
        }
        runCatching {
            SshExec.run(
                ssh,
                "($args >/dev/null 2>&1) >/dev/null 2>&1 & echo __FLASHING__",
                15_000
            )
        }
        Unit
    }

    /** 读取 /etc/sysupgrade.conf（自定义备份 glob 列表）。 */
    suspend fun readSysupgradeConf(config: RouterConfig, ssh: SshConfig?): String =
        withContext(Dispatchers.IO) {
            runCatching { SshFiles.readText(ssh, "/etc/sysupgrade.conf") }.getOrDefault("")
        }

    /** 写回 /etc/sysupgrade.conf。 */
    suspend fun writeSysupgradeConf(config: RouterConfig, ssh: SshConfig?, content: String) =
        withContext(Dispatchers.IO) {
            SshFiles.upload(ssh, "/etc/sysupgrade.conf", content.toByteArray(Charsets.UTF_8))
            SshExec.run(ssh, "chmod 644 /etc/sysupgrade.conf; echo __OK__", 10_000)
        }
}
