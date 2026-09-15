package com.futurepath.actionbox.billing

/**
 * Pure percentage math for the paywall's yearly-plan "Save N%" badge — kept out of
 * [BillingRepository]/PaywallScreen so it's testable without a ProductDetails instance (which
 * can't be constructed directly; Play Billing's classes have no public constructors) and so the
 * badge is never a hardcoded guess: it's computed from whatever prices Play Billing actually
 * returns at runtime, correct for any currency/locale/promotional pricing Play Console applies.
 */
object SubscriptionSavingsCalculator {

    /**
     * Percent saved by paying [yearlyPriceMicros] once instead of [monthlyPriceMicros] twelve
     * times, rounded down to a whole percent. Null whenever a badge would be wrong or misleading:
     * either price non-positive (not loaded yet), or the yearly plan doesn't actually undercut
     * twelve months of the monthly plan (a 0% or negative "savings" badge is worse than no badge).
     */
    fun yearlySavingsPercent(monthlyPriceMicros: Long, yearlyPriceMicros: Long): Int? {
        if (monthlyPriceMicros <= 0 || yearlyPriceMicros <= 0) return null
        val annualizedMonthly = monthlyPriceMicros * 12
        if (yearlyPriceMicros >= annualizedMonthly) return null
        val percent = ((annualizedMonthly - yearlyPriceMicros) * 100 / annualizedMonthly).toInt()
        return percent.takeIf { it > 0 }
    }
}
