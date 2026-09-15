package com.futurepath.actionbox.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionSavingsCalculatorTest {

    @Test
    fun `actual configured prices - 2point99 monthly vs 24point99 yearly - saves 30 percent`() {
        val monthly = 2_990_000L // $2.99 in micros
        val yearly = 24_990_000L // $24.99 in micros
        assertEquals(30, SubscriptionSavingsCalculator.yearlySavingsPercent(monthly, yearly))
    }

    @Test
    fun `yearly priced at exactly 12 months of monthly saves nothing`() {
        val monthly = 5_000_000L
        val yearly = monthly * 12
        assertNull(SubscriptionSavingsCalculator.yearlySavingsPercent(monthly, yearly))
    }

    @Test
    fun `yearly priced higher than 12 months of monthly saves nothing`() {
        val monthly = 5_000_000L
        val yearly = monthly * 12 + 1
        assertNull(SubscriptionSavingsCalculator.yearlySavingsPercent(monthly, yearly))
    }

    @Test
    fun `zero or negative prices are treated as not yet loaded`() {
        assertNull(SubscriptionSavingsCalculator.yearlySavingsPercent(0, 24_990_000L))
        assertNull(SubscriptionSavingsCalculator.yearlySavingsPercent(2_990_000L, 0))
        assertNull(SubscriptionSavingsCalculator.yearlySavingsPercent(-1, 24_990_000L))
    }

    @Test
    fun `a tiny rounding-only saving of under 1 percent is suppressed rather than shown as 0 percent`() {
        val monthly = 1_000_000L
        val annualized = monthly * 12
        // 0.5% cheaper than annualized monthly — rounds down to 0, which must come back null
        // rather than a "Save 0%" badge.
        val yearly = annualized - (annualized / 200)
        assertNull(SubscriptionSavingsCalculator.yearlySavingsPercent(monthly, yearly))
    }
}
