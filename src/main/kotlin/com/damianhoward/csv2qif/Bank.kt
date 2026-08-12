package com.damianhoward.csv2qif

import com.damianhoward.csv2qif.readers.CryptoDotComReader
import com.damianhoward.csv2qif.readers.KiwibankReader
import com.damianhoward.csv2qif.readers.SantanderReader

internal enum class Bank(
    val cliName: String,
    val qifType: QifType,
) {
    KIWIBANK("kiwibank", QifType.BANK),
    SANTANDER("santander", QifType.CREDIT_CARD),
    CRYPTO_DOT_COM("cryptodotcom", QifType.CREDIT_CARD),
    ;

    fun reader(): BankCsvReader =
        when (this) {
            KIWIBANK -> KiwibankReader()
            SANTANDER -> SantanderReader()
            CRYPTO_DOT_COM -> CryptoDotComReader()
        }

    companion object {
        fun byName(name: String): Bank? = entries.firstOrNull { it.cliName.equals(name, ignoreCase = true) }
    }
}
