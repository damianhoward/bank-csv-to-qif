package com.damianhoward.csv2qif

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A single parsed transaction, decoupled from any specific bank's CSV layout.
 *
 * Sign convention: `amount` is positive for inflows (deposits, refunds) and
 * negative for outflows (purchases, withdrawals). This matches the QIF `T`
 * field convention and lets the writer pass `amount` through without sign
 * fixups.
 */
data class Transaction(
    val date: LocalDate,
    val payee: String,
    val memo: String,
    val amount: BigDecimal,
)
