package com.qinglong.panel.utils

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

object EnvironmentChecker {
    private const val ROOTFS_DIR = "linux-rootfs"
    private const val PROOT_FILE = "proot"
    private const val READY_MARKER = "environment_ready"
    private const val APK_ARCHIVE = "linux-rootfs.tar.gz"
    private const val PROOT_ASSET = "proot-aarch64"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isPRootInstalled(context: Context): Boolean =
        File(context.filesDir, PROOT_FILE).canExecute() && File(context.filesDir, ROOTFS_DIR + "/bin/sh").exists()

    fun isNodeJSInstalled(context: Context): Boolean =
        File(context.filesDir, READY_MARKER).exists() && File(context.filesDir, ROOTFS_DIR + "/usr/bin/node").exists()

    fun isQingLongInstalled(context: Context): Boolean =
        File(context.filesDir, READY_MARKER).exists() && File(context.filesDir, ROOTFS_DIR + "/usr/local/bin/qinglong").exists()

    fun installPRoot(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                val proot = File(context.filesDir, PROOT_FILE)
                if (!proot.exists()) context.assets.open(PROOT_ASSET).use { input ->
                    FileOutputStream(proot).use { output -> input.copyTo(output) }
                }
                proot.setExecutable(true, false)
                val rootfs = File(context.filesDir, ROOTFS_DIR)
                if (!File(rootfs, "bin/sh").exists()) {
                    context.assets.open(APK_ARCHIVE).use { input ->
                        extractTarGz(input, rootfs)
                    }
                }
                File(rootfs, "etc/resolv.conf").parentFile?.mkdirs()
                File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
                true
            } catch (e: Exception) {
                Timber.e(e, "Linux environment installation failed")
                false
            }
            mainHandler.post { callback(ok) }
        }.start()
    }

    fun installNodeJS(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val ok = runInLinux(context, "apk update && apk add --no-cache nodejs npm python3 py3-pip bash curl ca-certificates git")
            mainHandler.post { callback(ok) }
        }.start()
    }

    fun installQingLong(context: Context, callback: (Boolean) -> Unit) {
        Thread {
            val command = "mkdir -p /ql/data /ql/scripts /ql/log && export QL_DIR=/ql QL_DATA_DIR=/ql/data QL_PORT=5700 && npm install -g @whyour/qinglong && qinglong --version >/dev/null 2>&1 || true"
            val ok = runInLinux(context, command) && File(context.filesDir, ROOTFS_DIR + "/usr/local/bin/qinglong").exists()
            if (ok) File(context.filesDir, READY_MARKER).createNewFile()
            mainHandler.post { callback(ok) }
        }.start()
    }

    fun initialize(context: Context, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                if (!isPRootInstalled(context)) {
                    if (!installPRootBlocking(context)) return@Thread callbackMain(callback, false, "Linux 环境安装失败")
                }
                if (!isNodeJSInstalled(context)) {
                    if (!runInLinux(context, "apk update && apk add --no-cache nodejs npm python3 py3-pip bash curl ca-certificates git"))
                        return@Thread callbackMain(callback, false, "Node.js/Python 安装失败")
                }
                if (!isQingLongInstalled(context)) {
                    val cmd = "mkdir -p /ql/data /ql/scripts /ql/log && export QL_DIR=/ql QL_DATA_DIR=/ql/data QL_PORT=5700 && npm install -g @whyour/qinglong"
                    if (!runInLinux(context, cmd)) return@Thread callbackMain(callback, false, "青龙安装失败")
                    File(context.filesDir, READY_MARKER).createNewFile()
                }
                callbackMain(callback, true, "环境就绪")
            } catch (e: Exception) {
                Timber.e(e, "Environment initialization failed")
                callbackMain(callback, false, e.message ?: "环境初始化失败")
            }
        }.start()
    }

    private fun installPRootBlocking(context: Context): Boolean {
        val proot = File(context.filesDir, PROOT_FILE)
        if (!proot.exists()) context.assets.open(PROOT_ASSET).use { input -> FileOutputStream(proot).use { output -> input.copyTo(output) } }
        proot.setExecutable(true, false)
        val rootfs = File(context.filesDir, ROOTFS_DIR)
        if (!File(rootfs, "bin/sh").exists()) context.assets.open(APK_ARCHIVE).use { extractTarGz(it, rootfs) }
        File(rootfs, "etc/resolv.conf").apply { parentFile?.mkdirs(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        return proot.canExecute() && File(rootfs, "bin/sh").exists()
    }

    fun runInLinux(context: Context, command: String): Boolean {
        val proot = File(context.filesDir, PROOT_FILE)
        val rootfs = File(context.filesDir, ROOTFS_DIR)
        if (!proot.canExecute() || !File(rootfs, "bin/sh").exists()) return false
        return try {
            val pb = ProcessBuilder(
                proot.absolutePath, "--kill-on-exit", "-0", "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-w", "/root", "/bin/sh", "-lc", command
            )
            pb.environment()["HOME"] = "/root"
            pb.environment()["LANG"] = "C.UTF-8"
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) Timber.e("Linux command failed ($code): $output")
            else Timber.i("Linux command completed: $output")
            code == 0
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute Linux command")
            false
        }
    }

    private fun callbackMain(callback: (Boolean, String) -> Unit, ok: Boolean, msg: String) =
        mainHandler.post { callback(ok, msg) }

    private fun extractTarGz(input: java.io.InputStream, target: File) {
        target.mkdirs()
        TarArchiveInputStream(GZIPInputStream(input.buffered())).use { tar ->
            var entry = tar.nextTarEntry
            val root = target.canonicalFile
            while (entry != null) {
                val out = File(target, entry.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator) && out != root) throw SecurityException("Unsafe archive path")
                if (entry.isDirectory) out.mkdirs()
                else if (entry.isSymbolicLink) {
                    out.parentFile?.mkdirs()
                    if (out.exists() || out.isSymbolicLink) out.delete()
                    Os.symlink(entry.linkName, out.path)
                } else if (entry.isFile) {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                    out.setExecutable((entry.mode and 64) != 0 || (entry.mode and 8) != 0 || (entry.mode and 1) != 0, false)
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
