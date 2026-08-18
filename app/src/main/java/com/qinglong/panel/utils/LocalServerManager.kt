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

                val command = """
                    export HOME=/root
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    export QL_DIR=/ql
                    export QL_DATA_DIR=/ql/data
                    export QL_PORT=$port
                    export QlPort=$port
                    cd /ql
                    exec qinglong
                """.trimIndent()

                val pb = ProcessBuilder(
                    proot.absolutePath,
                    "--kill-on-exit",
                    "-0",
                    "-r", rootfs.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-w", "/root",
                    "/bin/sh", "-lc", command
                )
                pb.environment()["HOME"] = "/root"
                pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                pb.environment()["LANG"] = "C.UTF-8"
                pb.environment()["LC_ALL"] = "C.UTF-8"
                pb.redirectErrorStream(true)
                serverProcess = pb.start()

                val process = serverProcess!!
                launchOutputLogger(process)

                if (!waitForPort(port, 60_000)) {
                    if (process.isAlive) process.destroy()
                    post(onStart, false, "青龙服务启动超时，请检查环境日志")
                    return@launch
                }

                post(onStart, true, "服务器已启动")
            } catch (e: Exception) {
                post(onStart, false, "服务器启动失败：${e.message}")
            }
        }
    }

    private fun launchOutputLogger(process: Process) {
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { android.util.Log.i("QingLongServer", it) }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
            if (serverProcess?.isAlive == false) return false
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
