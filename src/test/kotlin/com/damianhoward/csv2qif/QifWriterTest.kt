package com.damianhoward.csv2qif

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.math.BigDecimal
import java.time.LocalDate

class QifWriterTest {
    @Test
    fun `writes canonical QIF for credit-card type with one inflow and one outflow`() {
        val transactions =
            listOf(
                Transaction(
                    date = LocalDate.of(2024, 1, 2),
                    payee = "Grocery Store",
                    memo = "Visa",
                    amount = BigDecimal("-45.20"),
                ),
                Transaction(
                    date = LocalDate.of(2024, 1, 5),
                    payee = "Refund Whitcoulls",
                    memo = "Visa",
                    amount = BigDecimal("12.50"),
                ),
            )
        val writer = StringWriter()
        QifWriter(QifType.CREDIT_CARD).write(transactions, writer)

        val expected =
            """
            !Type:CCard
            D02/01/2024
            MVisa
            PGrocery Store
            T-45.20
            ^
            D05/01/2024
            MVisa
            PRefund Whitcoulls
            T12.50
            ^

            """.trimIndent()
        assertEquals(expected, writer.toString())
    }

    @Test
    fun `writes empty body when given no transactions, but still emits header`() {
        val writer = StringWriter()
        QifWriter(QifType.BANK).write(emptyList(), writer)
        assertEquals("!Type:Bank\n", writer.toString())
    }

    @Test
    fun `default constructor uses CCard type`() {
        val writer = StringWriter()
        QifWriter().write(emptyList(), writer)
        assertEquals("!Type:CCard\n", writer.toString())
    }

    @Test
    fun `caret and newlines in payee and memo are stripped`() {
        val transactions =
            listOf(
                Transaction(
                    date = LocalDate.of(2024, 1, 2),
                    payee = "Some^Store",
                    memo = "line1\nline2\rline3",
                    amount = BigDecimal("-1.00"),
                ),
            )
        val writer = StringWriter()
        QifWriter(QifType.CREDIT_CARD).write(transactions, writer)

        val expected =
            """
            !Type:CCard
            D02/01/2024
            Mline1 line2line3
            PSomeStore
            T-1.00
            ^

            """.trimIndent()
        assertEquals(expected, writer.toString())
    }
}
