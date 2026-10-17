package com.allan.nottoday.remote

/**
 * Encurtar o domínio antes de pedir a liberação (advertise.cloudlinks.cn -> cloudlinks.cn), nunca até um sufixo público
 * (cn, com.br). Mesma regra do gateway (executor/domains.py: is_public_suffix), que recusa o que passar disso.
 */
object DomainEdit {
    // Segundo nível de domínios de país (com.br, co.uk, gov.cn...): o sufixo público tem duas partes.
    private val SLD = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go", "mil", "ltd", "plc", "sch", "nom", "ind", "firm", "gob")

    fun isPublicSuffix(domain: String): Boolean {
        val p = domain.split('.')
        return p.size < 2 || (p.size == 2 && p[1].length == 2 && p[0] in SLD)
    }

    /** Tira o rótulo da esquerda; null se o resultado seria um sufixo público. */
    fun shorter(domain: String): String? = domain.substringAfter('.', "").takeIf { it.isNotEmpty() && !isPublicSuffix(it) }

    /** O domínio e cada pai possível dele, do mais completo ao mais curto. */
    fun levels(domain: String): List<String> = generateSequence(domain) { shorter(it) }.toList()
}
