// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.agent

/**
 * Model pricing table and cost estimation.
 *
 * Prices are in USD per 1 million tokens.
 * Source: official DeepSeek pricing page as of 2026-06.
 */
object ModelPricing {

    data class Price(
        val inputPerMillion: Double,
        val outputPerMillion: Double
    )

    private val PRICES = mapOf(
        "deepseek-v4-flash" to Price(0.14, 0.28),
        "deepseek-v4-pro" to Price(0.435, 0.87),
    )

    /**
     * Estimate cost in USD for a given model and token counts.
     * Returns 0.0 if model is not found (e.g. local models).
     */
    fun estimateCost(model: String, inputTokens: Int, outputTokens: Int): Double {
        val price = findPrice(model) ?: return 0.0
        return (inputTokens * price.inputPerMillion / 1_000_000.0) +
               (outputTokens * price.outputPerMillion / 1_000_000.0)
    }

    /**
     * Get the price entry for a model, with fuzzy matching for dated variants.
     * e.g. "deepseek-v4-flash-2026-06-01" → strips date suffixes → matches "deepseek-v4-flash"
     */
    fun findPrice(model: String): Price? {
        if (model.isEmpty()) return null

        // Direct match
        PRICES[model]?.let { return it }

        // Strip common prefixes (OpenRouter format: "deepseek/deepseek-v4-flash")
        val stripped = if (model.contains("/")) model.substringAfterLast("/") else model
        PRICES[stripped]?.let { return it }

        // Strip date suffixes: "deepseek-v4-flash-2026-06-01" → "deepseek-v4-flash-2026-06" → ...
        var candidate = stripped
        val dateSuffixPattern = Regex("-\\d{2,4}$")
        while (dateSuffixPattern.containsMatchIn(candidate)) {
            candidate = candidate.replace(dateSuffixPattern, "")
            PRICES[candidate]?.let { return it }
        }

        return null
    }

    /**
     * Format cost as a human-readable string.
     * < $0.01 → "$0.001" (3 decimals)
     * >= $0.01 → "$0.02" (2 decimals)
     * >= $1.00 → "$1.23" (2 decimals)
     */
    fun formatCost(costUsd: Double): String {
        return when {
            costUsd < 0.001 -> "< $0.001"
            costUsd < 0.01 -> String.format("$%.3f", costUsd)
            else -> String.format("$%.2f", costUsd)
        }
    }

    /**
     * Format token count as human-readable.
     * < 1000 → "500"
     * >= 1000 → "8.2K"
     * >= 1000000 → "1.2M"
     */
    fun formatTokens(tokens: Int): String {
        return when {
            tokens < 1000 -> tokens.toString()
            tokens < 1_000_000 -> String.format("%.1fK", tokens / 1000.0)
            else -> String.format("%.1fM", tokens / 1_000_000.0)
        }
    }

    /**
     * Estimate how many agent steps a budget allows for a given model.
     * Assumes ~5000 tokens per step (input + output).
     */
    fun estimateSteps(model: String, budgetUsd: Double): Int {
        val price = findPrice(model) ?: return 0
        val costPerStep = (4000 * price.inputPerMillion / 1_000_000.0) +
                          (1000 * price.outputPerMillion / 1_000_000.0)
        return if (costPerStep > 0) (budgetUsd / costPerStep).toInt() else 0
    }
}
