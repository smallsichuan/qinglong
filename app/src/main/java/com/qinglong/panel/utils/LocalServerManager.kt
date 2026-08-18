package com.qinglong.panel.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LocalServerManager(private val context: Context) {
    private var serverProcess: Process? = null
    private var serverScope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startServer(port: Int, onStart: (Boolean, String) -> Unit) {
        stopServer()
        serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverScope?.launch {
            try {
                if (!isPortAvailable(port)) {
                    post(onStart, false, "端口 $port 已被占用")
                    return@launch
                }
                if (!EnvironmentChecker.isQingLongInstalled(context)) {
                    post(onStart, false, "青龙环境尚未安装")
                    return@launch
                }

                val rootfs = File(context.filesDir, "linux-rootfs")
                val proot = File(context.filesDir, "proot")
                File(rootfs, "ql/data").mkdirs()
                val command = "export HOME=/root QL_DIR=/ql QL_DATA_DIR=/ql/data QL_PORT=$port QlPort=$port; cd /ql; exec qinglong"

                val pb = ProcessBuilder(
                    proot.absolutePath, "--kill-on-exit", "-0", "-r", rootfs.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-w", "/root", "/bin/sh", "-lc", command
                )
                pb.redirectErrorStream(true)
                pb.environment()["HOME"] = "/root"
                pb.environment()["LANG"] = "C.UTF-8"
                serverProcess = pb.start()

                val process = serverProcess!!
                Thread {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { android.util.Log.i("QingLongServer", it) }
                        }
                    } catch (_: Exception) {}
                }.start()

                if (!waitForServer(port, 60_000)) {
                    if (process.isAlive) process.destroy()
                    post(onStart, false, "青龙服务启动超时，请检查运行日志")
                    return@launch
                }
                post(onStart, true, "服务器已启动")
            } catch (e: Exception) {
                post(onStart, false, "服务器启动失败：${e.message}")
            }
        }
    }

    private fun waitForServer(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            } catch (_: Exception) {
                if (serverProcess?.isAlive != true) return false
                Thread.sleep(500)
            }
        }
        return false
    }

    private fun post(callback: (Boolean, String) -> Unit, success: Boolean, message: String) {
        mainHandler.post { callback(success, message) }
    }

    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }

    fun stopServer() {
        try { serverProcess?.destroy() } catch (_: Exception) {}
        serverProcess = null
        serverScope?.cancel()
        serverScope = null
    }
}
