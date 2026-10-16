package com.allan.nottoday

import android.content.Context
import android.util.Base64

/**
 * Script PAC (assets/block-ip.pac) que o Chrome recebe por política: qualquer endereço IP público digitado na barra (ou em um
 * link) vai para um proxy morto e a página não abre; nomes de domínio passam direto (e pelo DNS filtrante). Redes locais
 * (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16), a Tailscale (100.64/10 e fd00::/8) e o próprio aparelho continuam liberados.
 * Só vale no Chrome: apps comuns não passam por aqui e seguem livres para falar por IP.
 * O mesmo arquivo foi validado no Chrome do desktop (decimal, hexadecimal, octal e IPv6) e é o que o script do Windows usa.
 */
object ChromeIpPolicy {
    const val ASSET = "block-ip.pac"

    fun pac(context: Context): String = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** `data:` URL com o PAC, no formato que a política ProxyPacUrl aceita. */
    fun url(pac: String): String = "data:application/x-ns-proxy-autoconfig;base64," +
        Base64.encodeToString(pac.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
