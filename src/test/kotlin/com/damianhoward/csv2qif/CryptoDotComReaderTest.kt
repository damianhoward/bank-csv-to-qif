package com.damianhoward.csv2qif

import com.damianhoward.csv2qif.readers.CryptoDotComReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.io.StringReader
import java.math.BigDecimal
import java.time.LocalDate

class CryptoDotComReaderTest {
    @Test
    fun `parses sample CSV using native GBP amount, takes ISO date prefix from timestamp`() {
        val transactions = readFixture("cryptodotcom-sample.csv")
        assertEquals(4, transactions.size, "header row should be skipped")

        assertEquals(
            Transaction(
                date = LocalDate.of(2024, 1, 2),
                payee = "Crypto Earn Deposit (USDC)",
                memo = "Crypto Earn Deposit (USDC)",
                amount = BigDecimal("-78.50"),
            ),
            transactions[0],
        )

        assertEquals(BigDecimal("0.04"), transactions[1].amount, "positive amounts pass through unchanged")
        assertEquals(BigDecimal("0.03"), transactions[2].amount)
        assertEquals(BigDecimal("78.50"), transactions[3].amount)
    }

    @Test
    fun `truncated rows are skipped silently`() {
        val csv = "2024-01-02,short,row\n"
        val parsed = StringReader(csv).use { CryptoDotComReader().parse(it) }
        assertTrue(parsed.transactions.isEmpty(), "rows with fewer than 8 columns must not produce transactions")
    }

    @Test
    fun `row with unparseable date is skipped`() {
        val csv = "not-a-date,Some Desc,Sec,3,4,5,6,1.00\n"
        val parsed = StringReader(csv).use { CryptoDotComReader().parse(it) }
        assertTrue(parsed.transactions.isEmpty())
    }

    @Test
    fun `row with missing amount throws`() {
        val csv = "2024-01-02,Desc,Sec,3,4,5,6,\n"
        assertThrows(IllegalArgumentException::class.java) {
            StringReader(csv).use { CryptoDotComReader().parse(it) }
        }
    }

    private fun readFixture(name: String): List<Transaction> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }
        return InputStreamReader(stream).use { CryptoDotComReader().parse(it) }.transactions
    }
}
