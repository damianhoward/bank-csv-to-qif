package com.damianhoward.csv2qif

import java.io.IOException
import java.io.PrintStream
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(run(args, System.out, System.err))
}

/**
 * Pure-function entry point: returns the exit code rather than calling
 * `exitProcess`, so it can be exercised from tests without killing the JVM.
 * Exit codes follow the BSD `sysexits.h` convention: 64 = usage error,
 * 65 = input data unparseable (including `--strict` refusing a file with
 * unrecognised rows among its data), 66 = input file missing/unreadable,
 * 73 = output file unwritable, 1 = ran successfully but produced no
 * transactions, 0 = success.
 */
fun run(
    args: Array<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val parsed =
        parseArgs(args) ?: run {
            printUsage(err)
            return 64
        }

    val bank = Bank.byName(parsed.bankName)
    if (bank == null) {
        err.println("Unknown bank: '${parsed.bankName}'.")
        printUsage(err)
        return 64
    }

    // An inverted range selects nothing, and without this the run ends on "No transactions
    // parsed", which points at the input file for a mistake in the arguments — the wrong place to
    // start looking when a statement appears to be empty.
    if (parsed.from != null && parsed.to != null && parsed.from.isAfter(parsed.to)) {
        err.println("--from ${parsed.from} is after --to ${parsed.to}; that range selects nothing.")
        return 64
    }

    val input = Paths.get(parsed.inputPath)
    if (!Files.isReadable(input)) {
        err.println("Cannot read input file: ${parsed.inputPath}")
        return 66
    }

    val result =
        try {
            Files.newBufferedReader(input).use { bank.reader().parse(it) }
        } catch (e: Exception) {
            // A row the reader can't interpret (e.g. a garbage amount) or an I/O failure mid-read:
            // report it as a data error rather than dumping a stack trace.
            err.println("Failed to parse ${parsed.inputPath}: ${e.message}")
            return 65
        }
    val allTransactions = result.transactions
    val transactions =
        allTransactions.filter { txn ->
            (parsed.from == null || !txn.date.isBefore(parsed.from)) &&
                (parsed.to == null || !txn.date.isAfter(parsed.to))
        }

    if (parsed.verbose) {
        val outOfRange = allTransactions.size - transactions.size
        transactions.forEach { err.println("  parsed: ${it.date} ${it.amount.toPlainString()} | ${it.payee}") }
        if (outOfRange > 0) err.println("  ($outOfRange transaction(s) outside --from/--to range)")
        result.headerRows.forEach { err.println("  header: line ${it.lineNumber} (${it.reason})") }
    }

    // Rows the reader declined after the first transaction had already been read sit among the data,
    // so each one is a row that should probably have been money. Reported whether or not --strict is
    // set: a conversion that quietly holds fewer transactions than its statement is the failure this
    // accounting exists to make visible.
    result.droppedRows.forEach { err.println("Unrecognised row at line ${it.lineNumber} (${it.reason}): ${it.raw}") }
    if (result.droppedRows.isNotEmpty() && parsed.strict) {
        err.println(
            "--strict: refusing to write ${parsed.outputPath} — " +
                "${result.droppedRows.size} of ${result.rowsRead} rows were not recognised.",
        )
        return 65
    }

    if (transactions.isEmpty()) {
        err.println("No transactions parsed from ${parsed.inputPath}; nothing written.")
        return 1
    }

    val output = Paths.get(parsed.outputPath)
    try {
        writeAtomically(output) { QifWriter(bank.qifType).write(transactions, it) }
    } catch (e: IOException) {
        err.println("Cannot write output file ${parsed.outputPath}: ${e.message}")
        return 73
    }
    // Every row is accounted for on one line: what went in, what came out, and where the difference
    // went. Without it "Wrote 42 transactions" is unfalsifiable — it reads the same whether the
    // statement held 42 or 50.
    out.println(
        "Wrote ${transactions.size} transactions to ${parsed.outputPath} " +
            "(${result.rowsRead} rows read: ${result.transactions.size} transactions, " +
            "${result.headerRows.size} header, ${result.droppedRows.size} unrecognised" +
            if (allTransactions.size != transactions.size) {
                ", ${allTransactions.size - transactions.size} outside --from/--to)"
            } else {
                ")"
            },
    )
    return 0
}

/**
 * Writes through a sibling temporary file and moves it into place, so [target] is either its
 * previous contents or the complete new ones — never a half-written file.
 *
 * Opening the target directly truncates it before the first byte is written, so a failure partway
 * through (a full disk, an I/O error, a kill) replaced a good statement with a corrupt one that
 * still looks like a QIF. A conversion that fails should cost the user nothing.
 *
 * The temporary sits beside the target rather than in the system temp directory: the move is only
 * atomic within a filesystem, and the two are not reliably on the same one.
 */
private fun writeAtomically(
    target: Path,
    write: (Writer) -> Unit,
) {
    val temp = Files.createTempFile(target.toAbsolutePath().parent, target.fileName.toString(), ".tmp")
    try {
        Files.newBufferedWriter(temp).use(write)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}

internal data class ParsedArgs(
    val bankName: String,
    val inputPath: String,
    val outputPath: String,
    val from: LocalDate?,
    val to: LocalDate?,
    val verbose: Boolean,
    val strict: Boolean,
)

internal fun parseArgs(args: Array<String>): ParsedArgs? {
    var from: LocalDate? = null
    var to: LocalDate? = null
    var verbose = false
    var strict = false
    val positional = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "-v", "--verbose" -> verbose = true
            "--strict" -> strict = true
            "--from" -> {
                if (i + 1 >= args.size) return null
                from = parseIsoDate(args[i + 1]) ?: return null
                i++
            }
            "--to" -> {
                if (i + 1 >= args.size) return null
                to = parseIsoDate(args[i + 1]) ?: return null
                i++
            }
            else -> positional.add(arg)
        }
        i++
    }

    if (positional.size != 3) return null
    return ParsedArgs(positional[0], positional[1], positional[2], from, to, verbose, strict)
}

private fun parseIsoDate(s: String): LocalDate? =
    try {
        LocalDate.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }

private fun printUsage(err: PrintStream) {
    err.println("Usage: bank-csv-to-qif [options] <bank> <input.csv> <output.qif>")
    err.println("Options:")
    err.println("  --from YYYY-MM-DD   only include transactions on or after this date")
    err.println("  --to YYYY-MM-DD     only include transactions on or before this date")
    err.println("  -v, --verbose       print each parsed transaction (to stderr)")
    err.println("  --strict            fail instead of writing when a row among the data is unrecognised")
    err.println("Banks: ${Bank.entries.joinToString(", ") { it.cliName }}")
}
