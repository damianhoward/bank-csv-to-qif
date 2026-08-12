package com.damianhoward.csv2qif

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class MainTest {
    private data class CliResult(
        val code: Int,
        val stdout: String,
        val stderr: String,
    )

    @Test
    fun `no args prints usage and returns 64`() {
        val r = invoke()
        assertEquals(64, r.code)
        assertTrue(r.stderr.contains("Usage:"))
        assertTrue(r.stderr.contains("kiwibank"))
        assertTrue(r.stderr.contains("santander"))
        assertTrue(r.stderr.contains("cryptodotcom"))
    }

    @Test
    fun `too few args returns 64`() {
        assertEquals(64, invoke("kiwibank", "in.csv").code)
    }

    @Test
    fun `unknown bank reports and returns 64`(
        @TempDir tmp: Path,
    ) {
        val input = tmp.resolve("input.csv").also { it.writeText("dummy") }
        val output = tmp.resolve("out.qif")
        val r = invoke("hsbc", input.toString(), output.toString())
        assertEquals(64, r.code)
        assertTrue(r.stderr.contains("Unknown bank: 'hsbc'"))
    }

    @Test
    fun `unreadable input returns 66`(
        @TempDir tmp: Path,
    ) {
        val output = tmp.resolve("out.qif")
        val r = invoke("kiwibank", tmp.resolve("does-not-exist.csv").toString(), output.toString())
        assertEquals(66, r.code)
        assertTrue(r.stderr.contains("Cannot read input"))
    }

    @Test
    fun `unparseable amount returns 65 with a clean message`(
        @TempDir tmp: Path,
    ) {
        val csv = "ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,not-a-number,954.80\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("kiwibank", input.toString(), output.toString())
        assertEquals(65, r.code)
        assertTrue(r.stderr.contains("Failed to parse"), "expected a clean data error, got: ${r.stderr}")
    }

    @Test
    fun `unwritable output returns 73 with a clean message`(
        @TempDir tmp: Path,
    ) {
        val csv = "ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("missing-dir").resolve("out.qif") // parent doesn't exist
        val r = invoke("kiwibank", input.toString(), output.toString())
        assertEquals(73, r.code)
        assertTrue(r.stderr.contains("Cannot write output"), "expected a clean write error, got: ${r.stderr}")
    }

    @Test
    fun `csv with no transactions returns 1 and writes nothing`(
        @TempDir tmp: Path,
    ) {
        val input = tmp.resolve("empty.csv").also { it.writeText("just,a,header,row,no,money,in\n") }
        val output = tmp.resolve("out.qif")
        val r = invoke("kiwibank", input.toString(), output.toString())
        assertEquals(1, r.code)
        assertTrue(r.stderr.contains("No transactions"))
        assertTrue(!Files.exists(output), "output file must not be created when nothing was parsed")
    }

    @Test
    fun `happy path writes correct QIF and reports count`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Account number,Account name,Date,Other Party,Particulars,Money In,Money Out,Balance
            ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80
            ACC,Cheque,05/01/2024,Refund,Returned item,12.50,,967.30
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("kiwibank", input.toString(), output.toString())
        assertEquals(0, r.code)
        assertTrue(r.stdout.contains("Wrote 2 transactions"))

        val written = Files.readString(output)
        assertTrue(written.startsWith("!Type:Bank\n"))
        assertTrue(written.contains("D02/01/2024"))
        assertTrue(written.contains("T-45.20"))
        assertTrue(written.contains("D05/01/2024"))
        assertTrue(written.contains("T12.50"))
    }

    @Test
    fun `reconciliation line accounts for every row read`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Account number,Account name,Date,Other Party,Particulars,Money In,Money Out,Balance
            ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80
            ACC,Cheque,05/01/2024,Refund,Returned item,12.50,,967.30
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")

        val r = invoke("kiwibank", input.toString(), output.toString())

        assertEquals(0, r.code)
        // Without the counts, "Wrote 2 transactions" reads the same whether the file held 2 or 20.
        assertTrue(
            r.stdout.contains("3 rows read: 2 transactions, 1 header, 0 unrecognised"),
            "expected a reconciliation of input to output, got: ${r.stdout}",
        )
    }

    @Test
    fun `an unrecognised row among the data is reported and strict refuses to write`(
        @TempDir tmp: Path,
    ) {
        // The third row's date format differs from the rest — the kind of mid-file change that used
        // to remove a transaction from the export while the run still reported success.
        val csv =
            """
            Account number,Account name,Date,Other Party,Particulars,Money In,Money Out,Balance
            ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80
            ACC,Cheque,2024-01-05,Refund,Returned item,12.50,,967.30
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")

        val lenient = invoke("kiwibank", input.toString(), output.toString())
        assertEquals(0, lenient.code, "the default stays permissive")
        assertTrue(lenient.stderr.contains("Unrecognised row at line 3"), lenient.stderr)
        assertTrue(lenient.stderr.contains("Refund"), "the offending row is echoed so it can be found")
        assertTrue(lenient.stdout.contains("1 unrecognised"))
        assertTrue(Files.exists(output))

        Files.delete(output)
        val strict = invoke("--strict", "kiwibank", input.toString(), output.toString())
        assertEquals(65, strict.code)
        assertTrue(strict.stderr.contains("refusing to write"), strict.stderr)
        assertTrue(Files.notExists(output), "--strict must not leave a short export behind")
    }

    @Test
    fun `strict accepts a file whose only skips are its header`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Account number,Account name,Date,Other Party,Particulars,Money In,Money Out,Balance
            ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")

        val r = invoke("--strict", "kiwibank", input.toString(), output.toString())

        assertEquals(0, r.code, "a header row is expected noise, not data loss: ${r.stderr}")
        assertTrue(Files.exists(output))
    }

    @Test
    fun `santander happy path produces CCard header`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Statement,Date,Card,Account,Memo,Description,Reference,Money In,Other,Money Out
            1,02/01/2024,1234****5678,Visa,Card,PURCHASE DOMESTIC TESCO,REF001,"","","£42.95"
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("santander", input.toString(), output.toString())
        assertEquals(0, r.code)
        val written = Files.readString(output)
        assertTrue(written.startsWith("!Type:CCard\n"))
        assertTrue(written.contains("T-42.95"))
    }

    @Test
    fun `cryptodotcom happy path produces CCard header`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Timestamp (UTC),Transaction Description,Currency,Amount,To Currency,To Amount,Native Currency,Native Amount,Native Amount (in USD),Transaction Kind
            2024-01-02 10:15:33,Crypto Earn Deposit,USDC,-100.00,,,GBP,-78.50,,crypto_earn_deposit
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("cryptodotcom", input.toString(), output.toString())
        assertEquals(0, r.code)
        val written = Files.readString(output)
        assertTrue(written.startsWith("!Type:CCard\n"))
        assertTrue(written.contains("T-78.50"))
    }

    @Test
    fun `bank name lookup is case-insensitive`(
        @TempDir tmp: Path,
    ) {
        val input =
            tmp.resolve("in.csv").also {
                it.writeText("0,1,02/01/2024,Grocery Store,Weekly shop,,45.20\n")
            }
        val output = tmp.resolve("out.qif")
        assertEquals(0, invoke("KIWIBANK", input.toString(), output.toString()).code)
    }

    @Test
    fun `--from filters out transactions before the cutoff`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            ACC,Cheque,01/01/2024,Old,ref-old,,10.00
            ACC,Cheque,05/01/2024,Keep,ref-keep,,20.00
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("--from", "2024-01-03", "kiwibank", input.toString(), output.toString())
        assertEquals(0, r.code)
        val written = Files.readString(output)
        assertTrue(written.contains("PKeep"), "Jan 5 row must be included")
        assertTrue(written.contains("T-20.00"))
        assertTrue(!written.contains("T-10.00"), "Jan 1 row must be excluded")
    }

    @Test
    fun `--to filters out transactions after the cutoff`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            ACC,Cheque,05/01/2024,Keep,ref-keep,,20.00
            ACC,Cheque,10/01/2024,Drop,ref-drop,,30.00
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("--to", "2024-01-07", "kiwibank", input.toString(), output.toString())
        assertEquals(0, r.code)
        val written = Files.readString(output)
        assertTrue(written.contains("T-20.00"))
        assertTrue(!written.contains("T-30.00"))
    }

    @Test
    fun `-v prints each parsed transaction to stderr`(
        @TempDir tmp: Path,
    ) {
        val input = tmp.resolve("in.csv").also { it.writeText("ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20\n") }
        val output = tmp.resolve("out.qif")
        val r = invoke("-v", "kiwibank", input.toString(), output.toString())
        assertEquals(0, r.code)
        assertTrue(r.stderr.contains("parsed:"))
        assertTrue(r.stderr.contains("Grocery Store"))
    }

    @Test
    fun `--from with invalid date returns 64`(
        @TempDir tmp: Path,
    ) {
        val input = tmp.resolve("in.csv").also { it.writeText("dummy\n") }
        val output = tmp.resolve("out.qif")
        assertEquals(64, invoke("--from", "not-a-date", "kiwibank", input.toString(), output.toString()).code)
    }

    @Test
    fun `--from without a value returns 64`(
        @TempDir tmp: Path,
    ) {
        val input = tmp.resolve("in.csv").also { it.writeText("dummy\n") }
        val output = tmp.resolve("out.qif")
        assertEquals(64, invoke("kiwibank", input.toString(), output.toString(), "--from").code)
    }

    @Test
    fun `verbose reports filtered-out count when --from skips rows`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            ACC,Cheque,01/01/2024,Old,ref-old,,10.00
            ACC,Cheque,05/01/2024,Keep,ref-keep,,20.00
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")
        val r = invoke("-v", "--from", "2024-01-03", "kiwibank", input.toString(), output.toString())
        assertEquals(0, r.code)
        assertTrue(r.stderr.contains("1 transaction(s) outside"))
    }

    private fun invoke(vararg args: String): CliResult {
        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val code = run(args.toList().toTypedArray(), PrintStream(stdoutBuf), PrintStream(stderrBuf))
        return CliResult(code, stdoutBuf.toString(), stderrBuf.toString())
    }

    @Test
    fun `--from after --to returns 64 and names the range`(
        @TempDir tmp: Path,
    ) {
        val input = tmp.resolve("in.csv").also { it.writeText("dummy\n") }
        val output = tmp.resolve("out.qif")

        // Previously this ran the whole conversion, matched nothing, and exited 1 with
        // "No transactions parsed" — which sends the user to look at their statement for a
        // mistake that is in their arguments.
        val r = invoke("--from", "2024-06-01", "--to", "2024-01-01", "kiwibank", input.toString(), output.toString())

        assertEquals(64, r.code)
        assertTrue(r.stderr.contains("--from 2024-06-01 is after --to 2024-01-01"), r.stderr)
        assertTrue(Files.notExists(output), "nothing is written for an impossible range")
    }

    @Test
    fun `an equal --from and --to is a valid single-day range`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Account number,Account name,Date,Other Party,Particulars,Money In,Money Out,Balance
            ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80
            ACC,Cheque,05/01/2024,Refund,Returned item,12.50,,967.30
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif")

        val r = invoke("--from", "2024-01-02", "--to", "2024-01-02", "kiwibank", input.toString(), output.toString())

        assertEquals(0, r.code, r.stderr)
        assertTrue(r.stdout.contains("Wrote 1 transactions"))
    }

    @Test
    fun `a successful run replaces an existing output and leaves no temporary behind`(
        @TempDir tmp: Path,
    ) {
        val csv =
            """
            Account number,Account name,Date,Other Party,Particulars,Money In,Money Out,Balance
            ACC,Cheque,02/01/2024,Grocery Store,Weekly shop,,45.20,954.80
            """.trimIndent() + "\n"
        val input = tmp.resolve("in.csv").also { it.writeText(csv) }
        val output = tmp.resolve("out.qif").also { it.writeText("!Type:Bank\nSTALE PREVIOUS EXPORT\n") }

        val r = invoke("kiwibank", input.toString(), output.toString())

        assertEquals(0, r.code, r.stderr)
        val written = Files.readString(output)
        assertTrue(written.contains("T-45.20"), written)
        assertTrue(!written.contains("STALE"), "the previous export is fully replaced, not appended to")
        // The write goes through a sibling temporary; a leaked one would accumulate next to every
        // statement the user converts.
        val leftovers = Files.list(tmp).use { paths -> paths.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
        assertTrue(leftovers.isEmpty(), "no temporary left behind, found $leftovers")
    }
}
