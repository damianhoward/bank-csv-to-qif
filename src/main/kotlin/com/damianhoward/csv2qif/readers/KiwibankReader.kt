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
 * Reader for Kiwibank's CSV export.
 *
 * Column layout (zero-indexed):
 *  - 2: date (dd/MM/yyyy)
 *  - 3: Other Party — the counterparty name, mapped to the QIF payee
 *  - 4: Particulars — the transaction reference, mapped to the QIF memo
 *  - 5: amount in (blank if outflow)
 *  - 6: amount out (blank if inflow)
 *
 * Other Party and Particulars are distinct fields in a Kiwibank export, so they
 * map to distinct QIF fields rather than duplicating one value into both.
 *
 * Lines without a parseable date in column 2 are not transactions: Kiwibank
 * exports open with a few header rows, and this is the simplest robust way to
 * skip them. They are recorded with a reason rather than dropped silently, so a
 * run can reconcile the rows it read against the transactions it wrote.
 */
class KiwibankReader : BankCsvReader {
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
        if (row.size() <= COL_MONEY_IN) {
            return RowOutcome.Skipped("only ${row.size()} columns, need at least ${COL_MONEY_IN + 1}")
        }
        val date = tryParseDate(row.get(COL_DATE)) ?: return RowOutcome.Skipped("no dd/MM/yyyy date in column $COL_DATE")
        val payee = row.get(COL_OTHER_PARTY)
        val memo = row.get(COL_PARTICULARS)
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

    private fun parseAmount(raw: String): BigDecimal? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        return BigDecimal(cleaned)
    }

    /**
     * Returns null for info/balance rows (no amount in either column); these
     * are skipped rather than treated as zero transactions.
     */
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
        private const val COL_DATE = 2
        private const val COL_OTHER_PARTY = 3
        private const val COL_PARTICULARS = 4
        private const val COL_MONEY_IN = 5
        private const val COL_MONEY_OUT = 6
    }
}
