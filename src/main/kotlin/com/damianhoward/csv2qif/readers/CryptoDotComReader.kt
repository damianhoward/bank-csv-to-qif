package com.damianhoward.csv2qif.readers

import com.damianhoward.csv2qif.BankCsvReader
import com.damianhoward.csv2qif.ParseResult
import com.damianhoward.csv2qif.RowOutcome
import com.damianhoward.csv2qif.Transaction
import com.damianhoward.csv2qif.toParseResult
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Reader for Crypto.com's CSV export.
 *
 * Column layout (zero-indexed):
 *  - 0: timestamp (the first 10 chars are an ISO-8601 date: yyyy-MM-dd)
 *  - 1: transaction description
 *  - 2: secondary description (concatenated to col 1 to form the payee)
 *  - 7: amount, signed (already negative for outflows)
 *
 * The first row in a Crypto.com export is a header line whose description
 * column contains "Transaction Description"; it's skipped.
 */
class CryptoDotComReader : BankCsvReader {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    override fun parse(input: Reader): ParseResult {
        val format =
            CSVFormat.DEFAULT
                .builder()
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get()
        return format.parse(input).toParseResult { row -> parseRow(row) }
    }

    private fun parseRow(row: CSVRecord): RowOutcome {
        if (row.size() < MIN_COLUMNS) {
            return RowOutcome.Skipped("only ${row.size()} columns, need at least $MIN_COLUMNS")
        }
        val description = row.get(COL_DESCRIPTION_PRIMARY)
        if (description.contains("Transaction Description")) return RowOutcome.Skipped("column header row")
        val secondary = row.get(COL_DESCRIPTION_SECONDARY)
        val payee = if (secondary.isBlank()) description else "$description ($secondary)"
        val date = tryParseDate(row.get(COL_DATE).take(10)) ?: return RowOutcome.Skipped("no yyyy-MM-dd date in column $COL_DATE")
        // A dated row with no amount is a transaction whose value could not be read, not noise —
        // it fails the run rather than being counted as a skip, because guessing zero moves money.
        val amount =
            parseAmount(row.get(COL_AMOUNT))
                ?: throw IllegalArgumentException("Missing amount in row $payee")
        return RowOutcome.Parsed(Transaction(date = date, payee = payee, memo = payee, amount = amount))
    }

    private fun tryParseDate(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw, dateFormat)
        } catch (_: Exception) {
            null
        }

    private fun parseAmount(raw: String): BigDecimal? {
        val cleaned =
            raw
                .replace("\"", "")
                .replace("£", "")
                .replace(",", "")
                .trim()
        if (cleaned.isEmpty()) return null
        return BigDecimal(cleaned)
    }

    companion object {
        private const val COL_DATE = 0
        private const val COL_DESCRIPTION_PRIMARY = 1
        private const val COL_DESCRIPTION_SECONDARY = 2
        private const val COL_AMOUNT = 7
        private const val MIN_COLUMNS = COL_AMOUNT + 1
    }
}
