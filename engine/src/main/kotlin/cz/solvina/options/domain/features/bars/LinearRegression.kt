package cz.solvina.options.domain.features.bars

/** Ordinary least-squares linear regression over a list of y-values (x = index). */
object LinearRegression {
    data class RegressionLine(
        val slope: Double,
        val intercept: Double,
    ) {
        /** Value on the regression line at bar index [index] (0 = first input value). */
        fun valueAt(index: Int): Double = slope * index + intercept
    }

    /**
     * Fits a regression line through [values] where x is the bar index (0..n-1).
     * Returns null if fewer than 2 values provided.
     */
    fun fit(values: List<Double>): RegressionLine? {
        val n = values.size
        if (n < 2) return null

        val xMean = (n - 1) / 2.0
        val yMean = values.average()

        var numerator = 0.0
        var denominator = 0.0
        for (i in values.indices) {
            val dx = i - xMean
            numerator += dx * (values[i] - yMean)
            denominator += dx * dx
        }

        val slope = if (denominator == 0.0) 0.0 else numerator / denominator
        val intercept = yMean - slope * xMean
        return RegressionLine(slope, intercept)
    }
}
