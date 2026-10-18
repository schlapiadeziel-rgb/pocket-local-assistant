package cn.pocketai.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.pocketai.assistant.core.UpdatePolicy
import cn.pocketai.assistant.core.DownloadControl
import cn.pocketai.assistant.core.ResumableDownload
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

data class AvailableUpdate(val versionCode: Long, val versionName: String, val url: String, val apiUrl: String, val sha256: String, val size: Long)

object AppUpdater {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var downloadControl: DownloadControl? = null
    @Volatile var status = "尚未检查更新"
    @Volatile var available: AvailableUpdate? = null
    private fun connect(url: String, binary: Boolean = false): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 30_000
        setRequestProperty("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
        setRequestProperty("User-Agent", "PocketLocalAssistant/0.1")
    }
    private fun json(url: String, binary: Boolean = false): JSONObject {
        val c = connect(url, binary)
        try {
            check(c.responseCode == 200 && c.url.protocol == "https") { if (c.responseCode == 404) "暂未发布可用更新" else "更新服务 HTTP ${c.responseCode}" }
            c.inputStream.use { input ->
                val buffer = ByteArray(1024); val bytes = java.io.ByteArrayOutputStream()
                while (true) { val n = input.read(buffer); if (n < 0) break; check(bytes.size() + n <= 128 * 1024) { "更新数据过大" }; bytes.write(buffer, 0, n) }
                return JSONObject(bytes.toString("UTF-8"))
            }
        } finally { c.disconnect() }
    }
    fun check(context: Context, callback: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) { callback("正在处理更新，请稍后"); return }
        status = "正在检查 GitHub 正式发布版本"
        executor.execute {
            val result = try {
                val release = json(UpdatePolicy.API)
                check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "没有新的正式版本" }
                val assets = release.getJSONArray("assets")
                val manifest = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == "update.json" }
                    ?: error("发布缺少更新清单")
                val manifestUrl = manifest.getString("browser_download_url")
                check(UpdatePolicy.validAssetUrl(manifestUrl, "update.json")) { "更新来源不匹配" }
                val manifestApi = manifest.getString("url")
                check(UpdatePolicy.validApiAssetUrl(manifestApi)) { "清单 API 来源不匹配" }
                val info = json(manifestApi, binary = true)
                check(info.getString("packageName") == context.packageName) { "更新包名不匹配" }
                val filename = info.getString("fileName")
                check(filename.matches(Regex("pocket-local-assistant-[0-9A-Za-z.\\-]+-arm64\\.apk"))) { "安装包名称不正确" }
                val url = info.getString("apkUrl")
                check(UpdatePolicy.validAssetUrl(url, filename)) { "安装包来源不匹配" }
                val hash = info.getString("sha256")
                check(UpdatePolicy.validDigest(hash)) { "更新校验值不正确" }
                val size = info.getLong("size")
                check(size in 1..(512L * 1024 * 1024)) { "更新包大小不正确" }
                val apkAsset = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("browser_download_url") == url && it.optLong("size") == size }
                    ?: error("发布附件与清单不匹配")
                val apiUrl = apkAsset.getString("url")
                check(UpdatePolicy.validApiAssetUrl(apiUrl)) { "安装包 API 来源不匹配" }
                val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                val candidate = AvailableUpdate(info.getLong("versionCode"), info.getString("versionName"), url, apiUrl, hash, size)
                check(info.getString("tag") == release.getString("tag_name")) { "更新版本标签不匹配" }
                available = candidate.takeIf { it.versionCode > current }
                if (available == null) "已经是最新正式版本" else "发现新版本 ${candidate.versionName}，可下载更新"
            } catch (e: Exception) { available = null; "检查未完成：${DownloadNetwork.explain(e)}" }
            status = result; busy.set(false); main.post { callback(result) }
        }
    }
    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, expected: AvailableUpdate) {
        check(file.length() == expected.size && ResumableDownload.hash(file) == expected.sha256) { "文件校验失败" }
        val pm = context.packageManager
        val incoming = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("不是有效的 APK")
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(incoming.packageName == context.packageName && incoming.longVersionCode == expected.versionCode && incoming.longVersionCode > current.longVersionCode) { "安装包版本或包名不正确" }
        fun signerSet(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val existing = signerSet(current); val proposed = signerSet(incoming)
        check(existing.isNotEmpty() && existing == proposed) { "安装包签名与当前应用不同，已拒绝安装" }
    }
    fun download(context: Context, callback: (String) -> Unit) {
        val update = available ?: run { callback("请先检查更新"); return }
        if (!busy.compareAndSet(false, true)) { callback("正在处理更新，请稍后"); return }
        status = "正在下载更新，请保持应用前台"
        executor.execute {
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            val partial = File(dir, "update.part"); val final = File(dir, "update.apk")
            val control = DownloadControl(); downloadControl = control
            val result = try {
                check(dir.usableSpace > update.size - ResumableDownload.partialBytes(partial, update.size, update.sha256) + 64 * 1024 * 1024) { "存储空间不足" }
                ResumableDownload.fetch(partial, update.size, update.sha256, control, { offset -> DownloadNetwork.open(update.apiUrl, offset, github = true) }) { count ->
                    status = if (count == update.size) "正在校验更新文件与签名" else "更新下载 ${count * 100 / update.size}% · 可暂停续传"
                }
                verify(context, partial, update)
                control.check()
                check(partial.renameTo(final)) { "更新文件保存失败" }
                ResumableDownload.forgetMetadata(partial)
                "更新已下载并通过签名校验，点击安装更新"
            } catch (e: Exception) { "更新未完成：${DownloadNetwork.explain(e)}" } finally { downloadControl = null }
            status = result; busy.set(false); main.post { callback(result) }
        }
    }
    fun pauseDownload() { downloadControl?.cancel() }
    fun install(activity: Activity): String? {
        val expected = available ?: return "请先检查并下载更新"
        val file = File(activity.filesDir, "updates/update.apk")
        if (!file.exists()) return "请先下载更新"
        // Hashing/signature validation already ran on the worker. Revalidate again on the worker before offering installer.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return "请允许此应用安装更新，然后返回并再次点击安装"
        }
        if (!busy.compareAndSet(false, true)) return "更新正忙，请稍后"
        executor.execute {
            try {
                verify(activity.applicationContext, file, expected)
                main.post {
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        AssistantState.service?.pause("准备安装更新，屏幕观察已暂停")
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: android.content.ActivityNotFoundException) { status = "系统没有可用的安装程序，请从发布页面安装" }
                    }
                }
            } catch (e: Exception) { status = "安装未完成：${DownloadNetwork.explain(e)}" }
            finally { busy.set(false) }
        }
        return null
    }
}
