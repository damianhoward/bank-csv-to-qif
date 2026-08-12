package com.damianhoward.csv2qif

import com.damianhoward.csv2qif.readers.KiwibankReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.math.BigDecimal
import java.time.LocalDate

class KiwibankReaderTest {
    @Test
    fun `parses sample CSV preserving sign convention and skipping balance rows`() {
        val transactions = readFixture("kiwibank-sample.csv")
        assertEquals(4, transactions.size, "header and balance rows should be skipped")

        assertEquals(
            Transaction(
                date = LocalDate.of(2024, 1, 2),
                payee = "Countdown",
                memo = "Weekly shop",
                amount = BigDecimal("-45.20"),
            ),
            transactions[0],
        )
        assertEquals(
            Transaction(
                date = LocalDate.of(2024, 1, 3),
                payee = "Acme Payroll",
                memo = "Salary",
                amount = BigDecimal("3500.00"),
            ),
            transactions[1],
        )
        assertEquals(BigDecimal("-4.50"), transactions[2].amount)
        assertEquals(BigDecimal("12.50"), transactions[3].amount)
    }

    @Test
    fun `every row read is accounted for as a transaction, a header, or a drop`() {
        val result = parseFixture("kiwibank-sample.csv")

        assertEquals(
            result.rowsRead,
            result.transactions.size + result.headerRows.size + result.droppedRows.size,
            "no row may be read and then vanish",
        )
        assertEquals(4, result.transactions.size)
        org.junit.jupiter.api.Assertions.assertTrue(
            result.headerRows.isNotEmpty(),
            "the fixture opens with header rows, and they must be recorded rather than discarded",
        )
        result.headerRows.forEach {
            org.junit.jupiter.api.Assertions
                .assertTrue(it.reason.isNotBlank(), "every skip states why")
            org.junit.jupiter.api.Assertions
                .assertTrue(it.lineNumber > 0, "every skip names its line")
        }
    }

    @Test
    fun `a row the reader cannot recognise among the data is reported as a drop, not lost`() {
        // A real statement whose date format changes partway through: the first row parses, so the
        // second is not header noise — it is a transaction the reader failed to read.
        val csv =
            """
            a,b,02/01/2024,Countdown,Weekly shop,,45.20
            a,b,2024-01-03,Acme Payroll,Salary,3500.00,
            """.trimIndent()

        val result = java.io.StringReader(csv).use { KiwibankReader().parse(it) }

        assertEquals(1, result.transactions.size)
        assertEquals(0, result.headerRows.size)
        assertEquals(1, result.droppedRows.size, "the unreadable row must be reported, not skipped away")
        assertEquals("no dd/MM/yyyy date in column 2", result.droppedRows[0].reason)
        org.junit.jupiter.api.Assertions
            .assertTrue(result.droppedRows[0].raw.contains("Acme Payroll"))
    }

    private fun parseFixture(name: String): ParseResult {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }
        return InputStreamReader(stream).use { KiwibankReader().parse(it) }
    }

    private fun readFixture(name: String): List<Transaction> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }
        return InputStreamReader(stream).use { KiwibankReader().parse(it) }.transactions
    }
}
