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
        val nonce = B64.enc(ByteArray(12).also { SecureRandom().nextBytes(it) })
        val headers = DevAuth.headers(signer::sign, spki, method, path, data, System.currentTimeMillis() / 1000, nonce)
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
