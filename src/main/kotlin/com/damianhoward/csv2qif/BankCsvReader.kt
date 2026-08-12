package com.damianhoward.csv2qif

import org.apache.commons.csv.CSVRecord
import java.io.Reader

/**
 * A row the reader could not turn into a [Transaction], kept so the run can account for it.
 *
 * Every statement has some of these legitimately — the column banner, an export title, a closing
 * balance. The problem is that a row the reader simply failed to recognise looks exactly the same
 * from the outside, and a financial export that quietly holds fewer transactions than its input is
 * worse than one that fails.
 */
data class SkippedRow(
    val lineNumber: Long,
    val reason: String,
    val raw: String,
)

/**
 * What a reader made of a statement: the transactions, and every row it did not.
 *
 * Skips are split by position rather than lumped together, because the two mean different things.
 * A skip before the first transaction is the header the file opens with, and is expected. A skip
 * after it sits among the data, so it is a row that should probably have been money — the case
 * `--strict` refuses to write.
 */
data class ParseResult(
    val transactions: List<Transaction>,
    val headerRows: List<SkippedRow>,
    val droppedRows: List<SkippedRow>,
) {
    val rowsRead: Int get() = transactions.size + headerRows.size + droppedRows.size
}

/** Either a parsed transaction, or the reason this row is not one. */
sealed interface RowOutcome {
    @JvmInline
    value class Parsed(
        val transaction: Transaction,
    ) : RowOutcome

    @JvmInline
    value class Skipped(
        val reason: String,
    ) : RowOutcome
}

/**
 * Parses a bank's CSV statement into a stream of [Transaction]s.
 *
 * Implementations are responsible for:
 *  - knowing the bank's column layout and date format,
 *  - stripping bank-specific noise from the payee field (e.g. "PURCHASE DOMESTIC " prefixes),
 *  - producing the correct sign on the amount (negative = outflow), and
 *  - giving a reason for every row they decline, so the run can reconcile its input against its
 *    output rather than reporting success over a short file.
 *
 * Implementations should be stateless and safe to reuse across multiple files.
 */
interface BankCsvReader {
    fun parse(input: Reader): ParseResult
}

/**
 * Applies [parseRow] to every record and sorts the results into transactions, header rows and
 * dropped rows. Shared because all three readers need identical accounting, and the classification
 * rule is a property of the format rather than of any one bank.
 */
internal fun Iterable<CSVRecord>.toParseResult(parseRow: (CSVRecord) -> RowOutcome): ParseResult {
    val transactions = mutableListOf<Transaction>()
    val headerRows = mutableListOf<SkippedRow>()
    val droppedRows = mutableListOf<SkippedRow>()

    for (row in this) {
        when (val outcome = parseRow(row)) {
            is RowOutcome.Parsed -> transactions += outcome.transaction
            is RowOutcome.Skipped -> {
                val skipped = SkippedRow(row.recordNumber, outcome.reason, row.joinToString(","))
                if (transactions.isEmpty()) headerRows += skipped else droppedRows += skipped
            }
        }
    }
    return ParseResult(transactions, headerRows, droppedRows)
}
