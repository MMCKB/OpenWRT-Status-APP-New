package com.mmckb.openwrtstatus.data.ssh

/**
 * POSIX shell 单引号转义：把任意字符串安全地嵌入 `sh -c` 脚本。
 *
 * 单引号内的字符除 `'` 外都不具特殊含义，因此只需把 `'` 替换成 `'\''`
 * （闭合 → 转义单引号 → 重新开启），再整体包一层单引号。
 *
 * 该函数此前在 6 个 Client 里各有一份私有副本，且有多处拼接绕开它
 * （见 S-01）——统一到此处，新增脚本拼接一律走 [shq]。
 */
fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"
