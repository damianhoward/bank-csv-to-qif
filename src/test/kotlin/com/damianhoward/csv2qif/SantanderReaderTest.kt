package com.damianhoward.csv2qif

import com.damianhoward.csv2qif.readers.SantanderReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.math.BigDecimal
import java.time.LocalDate

class SantanderReaderTest {
    @Test
    fun `parses sample CSV, strips boilerplate prefixes, drops INITIAL BALANCE`() {
        val transactions = readFixture("santander-sample.csv")
        assertEquals(6, transactions.size, "INITIAL BALANCE and header rows should be dropped")

        assertEquals("TESCO STORES LONDON", transactions[0].payee)
        assertEquals(BigDecimal("-42.95"), transactions[0].amount)
        assertEquals(LocalDate.of(2024, 1, 2), transactions[0].date)

        assertEquals("AMAZON DE", transactions[1].payee, "PURCHASE - INTERNATIONAL prefix stripped")
        assertEquals("PRET", transactions[2].payee, "APPLE PAY prefix stripped")
        assertEquals("NETFLIX", transactions[3].payee, "RECURRENT TRANSACTION prefix stripped")
        assertEquals("PUBLIC TRANSPORT", transactions[4].payee, "CARD PAYMENT TO prefix stripped")

        assertEquals(BigDecimal("25.00"), transactions[5].amount, "refund row produces positive amount")
        assertEquals("REFUND ASOS", transactions[5].payee, "no prefix to strip on REFUND row")
    }

    @Test
    fun `memo column carries the card identifier`() {
        val transactions = readFixture("santander-sample.csv")
        assertTrue(transactions.all { it.memo == "Visa" })
    }

    private fun readFixture(name: String): List<Transaction> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }
        return InputStreamReader(stream).use { SantanderReader().parse(it) }.transactions
    }
}
