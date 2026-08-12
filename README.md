# Bank CSV → QIF Converter

[![CI](https://github.com/damianhoward/bank-csv-to-qif/actions/workflows/ci.yml/badge.svg)](https://github.com/damianhoward/bank-csv-to-qif/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damianhoward/bank-csv-to-qif/actions/workflows/codeql.yml/badge.svg)](https://github.com/damianhoward/bank-csv-to-qif/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/damianhoward/bank-csv-to-qif/graph/badge.svg)](https://codecov.io/gh/damianhoward/bank-csv-to-qif)
[![Release](https://img.shields.io/github/v/release/damianhoward/bank-csv-to-qif)](https://github.com/damianhoward/bank-csv-to-qif/releases)

Converts bank CSV statement exports into [QIF (Quicken Interchange Format)](https://en.wikipedia.org/wiki/Quicken_Interchange_Format), the format used by Quicken, MoneyDance, GnuCash, KMyMoney, Microsoft Money, and a long tail of legacy personal-finance tools that long predate Open Banking.

Built because every bank exports a _slightly_ different CSV layout — different column orders, different date formats, different ways of expressing money in versus money out — and every finance tool wants QIF. This bridges the gap with a per-bank parser, a canonical QIF writer, and a tiny CLI.

## Supported banks

| Bank           | CLI name       | Notes                                                                                                                                                                                                                                                                              |
| -------------- | -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kiwibank (NZ)  | `kiwibank`     | Date `dd/MM/yyyy`, distinct Other Party (→ payee) and Particulars (→ memo) columns, separate Money In / Money Out columns. Output as `!Type:Bank`.                                                                                                                                 |
| Santander (UK) | `santander`    | Date `dd/MM/yyyy`, `£` amounts in quoted fields, strips `PURCHASE - INTERNATIONAL ` / `PURCHASE - DOMESTIC ` / `PURCHASE DOMESTIC ` / `RECURRENT TRANSACTION ` / `CARD PAYMENT TO ` / `APPLE PAY ` prefixes from the payee. Skips `INITIAL BALANCE` rows. Output as `!Type:CCard`. |
| Crypto.com     | `cryptodotcom` | ISO timestamp (`yyyy-MM-dd HH:mm:ss`), signed native-currency amount, payee is `description (secondary description)` when a secondary description is present, else just `description`. Output as `!Type:CCard`.                                                                    |

## Use it from the CLI

### Download a release (no build required)

Grab the latest tarball or zip from [GitHub Releases](https://github.com/damianhoward/bank-csv-to-qif/releases), extract it, and run:

```bash
tar -xf bank-csv-to-qif-1.0.0.tar
./bank-csv-to-qif-1.0.0/bin/bank-csv-to-qif kiwibank statement.csv statement.qif
```

Each release ships a `SHA256SUMS.txt` next to the archives. Requires JDK 25 on `PATH`.

### Build it locally

```bash
./gradlew installDist
./build/install/bank-csv-to-qif/bin/bank-csv-to-qif kiwibank statement.csv statement.qif
```

Exit codes follow the BSD `sysexits.h` convention: `64` for bad usage, `65` for input data the reader can't parse (including `--strict` refusing a file), `66` for an unreadable input file, `73` for an unwritable output file, `1` if no transactions were parseable (so the output file isn't created), `0` on success.

Every run reconciles its input against its output:

```
Wrote 42 transactions to statement.qif (44 rows read: 42 transactions, 2 header, 0 unrecognised)
```

That line exists because `Wrote 42 transactions` alone is unfalsifiable — it reads the same whether the statement held 42 rows or 50. A statement's header rows are expected and counted as such. A row the reader declines _after_ the first transaction is different: it sits among the data, so it is probably a transaction that could not be read, and it is echoed to stderr with its line number and the reason.

```bash
./bank-csv-to-qif-1.0.0/bin/bank-csv-to-qif kiwibank statement.csv statement.qif --from 2024-01-01 --to 2024-03-31 -v
```

`--from` / `--to` (ISO dates, either or both) keep only transactions in that range; `-v` / `--verbose` prints each parsed transaction to stderr, plus the header rows and a count of any rows the date range excluded; `--strict` refuses to write the file at all when any row among the data went unrecognised, which is the right default for an export you intend to import into an accounts package.

## Use it as a library

```kotlin
val result = Files.newBufferedReader(Paths.get("santander-2024-01.csv"))
    .use { SantanderReader().parse(it) }

check(result.droppedRows.isEmpty()) { "unreadable rows: ${result.droppedRows}" }

Files.newBufferedWriter(Paths.get("santander-2024-01.qif")).use {
    QifWriter(QifType.CREDIT_CARD).write(result.transactions, it)
}
```

`parse` returns a `ParseResult` rather than a bare list, so a caller can tell "this statement had two header rows" from "this statement had two rows I could not read". `Transaction` uses `LocalDate` for dates and `BigDecimal` for amounts (signed, positive for inflows). The original ad-hoc scripts these are descended from used `Double` for amounts, which is a real bug for any reasonable definition of "bank statement"; this version doesn't.

## Adding a new bank

Implement `BankCsvReader`:

```kotlin
class MyBankReader : BankCsvReader {
    override fun parse(input: Reader): ParseResult =
        CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(true).get()
            .parse(input)
            .toParseResult { row -> parseRow(row) }

    private fun parseRow(row: CSVRecord): RowOutcome =
        // RowOutcome.Parsed(transaction), or RowOutcome.Skipped("why") — never a bare null.
        // toParseResult sorts the skips into header rows and dropped rows for you.
}
```

The pattern in the existing readers:

- Use `CSVFormat.DEFAULT` from commons-csv for the parse (handles quoted fields with embedded commas correctly).
- Try to parse a date from the date column; if it fails, decline the row — that's how all three readers reject the header. Always give a reason: a skip with no reason is how a transaction disappears from a financial export without anyone noticing.
- Express the amount as a signed `BigDecimal` (positive for inflows, negative for outflows). The QIF writer doesn't add any sign fixups.
- Strip bank-specific noise from the payee in the reader, not the writer. That keeps `QifWriter` agnostic to where the data came from.

## Design notes

- **Readers are stateless.** A single `KiwibankReader` instance is safe to reuse across multiple files and threads.
- **QIF format choice (`!Type:Bank` vs `!Type:CCard`)** is per-bank, not per-row. Most banks export from a single account, so this is the right granularity.
- **No streaming.** Both reader and writer materialise the full transaction list. For bank statements (typically thousands of rows max), this is fine and avoids the complexity of streaming through commons-csv's iterator semantics.
- **No automatic categorisation, splits, or cleared-status.** QIF supports those; this writer intentionally doesn't. Categorisation belongs in the finance tool that imports the output, not here.

## Stack

- Kotlin 2.3.21 (JVM target 25)
- Java 25 toolchain
- Apache Commons CSV 1.14.1 (the one runtime dependency)
- JUnit Jupiter 6.1
- Gradle 9.6

## License

Apache 2.0 — see [LICENSE](LICENSE).
