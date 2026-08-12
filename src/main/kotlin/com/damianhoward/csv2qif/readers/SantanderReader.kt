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
 * Reader for Santander UK's CSV export.
 *
 * Column layout (zero-indexed):
 *  - 1: date (dd/MM/yyyy)
 *  - 3: card / memo
 *  - 5: description (used as the payee, after cleanup)
 *  - 7: money in (blank if outflow), as "£12.34"
 *  - 9: money out (blank if inflow), as "£12.34"
 *
 * Skips "INITIAL BALANCE" rows. The description column carries a lot of
 * boilerplate prefixes — see [PAYEE_PREFIXES_TO_STRIP] — which obscures the
 * actual merchant; those are stripped before emitting.
 */
class SantanderReader : BankCsvReader {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

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
        val date = tryParseDate(row.get(COL_DATE)) ?: return RowOutcome.Skipped("no dd/MM/yyyy date in column $COL_DATE")
        val rawDescription = row.get(COL_DESCRIPTION)
        if (rawDescription.contains("INITIAL BALANCE")) return RowOutcome.Skipped("opening balance, not a transaction")
        val payee = cleanPayee(rawDescription)
        val memo = row.get(COL_CARD)
        val moneyIn = parseAmount(row.get(COL_MONEY_IN))
        val moneyOut = if (row.size() > COL_MONEY_OUT) parseAmount(row.get(COL_MONEY_OUT)) else null
        val amount = signedAmount(moneyIn, moneyOut) ?: return RowOutcome.Skipped("no amount in or out")
        return RowOutcome.Parsed(Transaction(date = date, payee = payee, memo = memo, amount = amount))
    }

    private fun tryParseDate(raw: String): LocalDate? =
        try {
            LocalDate.parse(raw, dateFormat)
        } catch (_: Exception) {
            null
        }

    private fun cleanPayee(raw: String): String {
        var cleaned = raw
        for (prefix in PAYEE_PREFIXES_TO_STRIP) {
            cleaned = cleaned.replace(prefix, "")
        }
        return cleaned.trim()
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

    private fun signedAmount(
        moneyIn: BigDecimal?,
        moneyOut: BigDecimal?,
    ): BigDecimal? =
        when {
            moneyOut != null -> moneyOut.negate()
            moneyIn != null -> moneyIn
            else -> null
        }

    companion object {
        private const val COL_DATE = 1
        private const val COL_CARD = 3
        private const val COL_DESCRIPTION = 5
        private const val COL_MONEY_IN = 7
        private const val COL_MONEY_OUT = 9

        /** Money out may be absent on an inflow row, so the money-in column is the last required one. */
        private const val MIN_COLUMNS = COL_MONEY_IN + 1

        /**
         * Boilerplate prefixes Santander prepends to the merchant name. Stripped
         * to leave the actual payee. Order matters: longer/more specific prefixes
         * come first to avoid being shadowed by their shorter cousins.
         */
        private val PAYEE_PREFIXES_TO_STRIP =
            listOf(
                "PURCHASE - INTERNATIONAL ",
                "PURCHASE - DOMESTIC ",
                "PURCHASE DOMESTIC ",
                "RECURRENT TRANSACTION ",
                "CARD PAYMENT TO ",
                "APPLE PAY ",
            )
    }
}
