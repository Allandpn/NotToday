package com.allan.nottoday.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class GatewayException(val status: Int, message: String) : IOException(message)

/** Cliente HTTPS do gateway. Toda requisição é assinada com a chave do aparelho (veja protocol/SPEC.md). */
class GatewayApi(
    private val base: String,
    private val signer: DeviceSigner = DeviceKey,
    /** Só os testes de integração (gateway local sem TLS). */
    allowInsecure: Boolean = false,
) {
    private val spki: ByteArray by lazy { signer.spki() }

    init {
        require(base.startsWith("https://") || (allowInsecure && base.startsWith("http://"))) { "o gateway precisa ser HTTPS" }
    }

    /** `path` inclui a query. `waitS` é o tempo que o gateway pode segurar a resposta (long-poll). */
    fun call(method: String, path: String, body: Any? = null, waitS: Int = 0): Map<String, Any?> {
        val data = if (body != null) Json.encode(body).toByteArray(Charsets.UTF_8) else ByteArray(0)
        val headers = signedHeaders(method, path, data)
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = (waitS + 20) * 1000
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (method == "POST") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use { it.write(data) }
            }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.use { readCapped(it) } ?: ByteArray(0)
            val text = String(raw, Charsets.UTF_8)
            val parsed = try {
                if (text.isBlank()) emptyMap() else Json.parseObject(text)
            } catch (e: Json.JsonException) {
                emptyMap()
            }
            if (code !in 200..299) throw GatewayException(code, (parsed["error"] as? String) ?: "HTTP $code")
            return parsed
        } finally {
            c.disconnect()
        }
    }

    private fun signedHeaders(method: String, path: String, data: ByteArray): Map<String, String> {
        val nonce = B64.enc(ByteArray(12).also { SecureRandom().nextBytes(it) })
        return DevAuth.headers(signer::sign, spki, method, path, data, System.currentTimeMillis() / 1000, nonce)
    }

    class Download(val sha256: String, val size: Long)

    /**
     * GET assinado que grava o corpo em [dest] calculando o SHA-256 no caminho. Passa de [maxBytes] ou diverge do
     * Content-Length esperado: aborta (quem chama apaga o arquivo e confere hash e tamanho).
     */
    fun download(path: String, dest: java.io.File, maxBytes: Long): Download {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            signedHeaders("GET", path, ByteArray(0)).forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code !in 200..299) {
                val text = c.errorStream?.use { readCapped(it) }?.let { String(it, Charsets.UTF_8) }.orEmpty()
                val err = try {
                    if (text.isBlank()) null else Json.parseObject(text)["error"] as? String
                } catch (e: Json.JsonException) {
                    null
                }
                throw GatewayException(code, err ?: "HTTP $code")
            }
            val len = c.contentLengthLong
            if (len > maxBytes) throw IOException("arquivo maior que o autorizado")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var total = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("arquivo maior que o autorizado")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            return Download(md.digest().joinToString("") { "%02x".format(it) }, total)
        } finally {
            c.disconnect()
        }
    }

    private fun readCapped(s: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_RESPONSE) throw IOException("resposta grande demais")
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE = 4 * 1024 * 1024
    }
}
