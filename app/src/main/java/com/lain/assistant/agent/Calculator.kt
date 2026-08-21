package com.lain.assistant.agent

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Arithmetic and unit conversion, done properly instead of asked of a model.
 *
 * Small language models are unreliable at arithmetic in a specific and unhelpful
 * way: they produce a confident, well-formatted, wrong number. There is no prompt
 * that fixes that, and no amount of model capability that makes it a good use of a
 * network round trip. A recursive-descent parser gets it right every time, offline,
 * in microseconds.
 *
 * Scope is deliberately narrow — the four operations, powers, parentheses,
 * percentages and common unit conversions. Anything it can't parse with confidence
 * returns null and goes to the model, which is better at word problems than this
 * will ever be.
 */
object Calculator {

    /** A resolved calculation: the answer, and how it was read, so the reply can show its working. */
    data class Result(val expression: String, val value: Double) {
        /** Formats without trailing noise: 7 rather than 7.0, 2.5 rather than 2.4999999999999996. */
        fun pretty(): String = format(value)
    }

    // ------------------------------------------------------------------ entry

    /** @return the answer, or null when this isn't confidently a calculation. */
    fun evaluate(input: String): Result? {
        val text = normalise(input)
        if (text.isEmpty()) return null

        percentage(text)?.let { return it }
        conversion(text)?.let { return it }
        arithmetic(text)?.let { return it }
        return null
    }

    private fun normalise(input: String): String = input.trim().lowercase()
        .removePrefix("what's ").removePrefix("what is ").removePrefix("whats ")
        .removePrefix("calculate ").removePrefix("compute ").removePrefix("work out ")
        .removePrefix("how much is ").removePrefix("how many ")
        .trimEnd('?', '.', '!')
        .replace("×", "*").replace("÷", "/").replace("−", "-")
        .replace(",", "")
        .trim()

    // ------------------------------------------------------------ percentages

    private val percentOf = Regex("^([\\d.]+)\\s*(?:%|percent)\\s+of\\s+([\\d.]+)$")
    private val percentChange = Regex("^([\\d.]+)\\s*(?:%|percent)\\s+(off|on|more than|less than)\\s+([\\d.]+)$")

    private fun percentage(text: String): Result? {
        percentOf.find(text)?.let { m ->
            val pct = m.groupValues[1].toDoubleOrNull() ?: return null
            val of = m.groupValues[2].toDoubleOrNull() ?: return null
            return Result("${format(pct)}% of ${format(of)}", pct / 100.0 * of)
        }
        percentChange.find(text)?.let { m ->
            val pct = m.groupValues[1].toDoubleOrNull() ?: return null
            val base = m.groupValues[3].toDoubleOrNull() ?: return null
            val delta = pct / 100.0 * base
            val up = m.groupValues[2] == "on" || m.groupValues[2] == "more than"
            return Result(
                "${format(pct)}% ${m.groupValues[2]} ${format(base)}",
                if (up) base + delta else base - delta
            )
        }
        return null
    }

    // ------------------------------------------------------------ conversions

    /** Everything reduces to a base unit, so any pair within a family converts. */
    private data class Unit(val family: String, val perBase: Double, val canonical: String)

    private val units: Map<String, Unit> = buildMap {
        fun put(unit: Unit, vararg names: String) = names.forEach { put(it, unit) }

        // length, base metre
        put(Unit("length", 1.0, "metres"), "m", "metre", "metres", "meter", "meters")
        put(Unit("length", 1000.0, "kilometres"), "km", "kilometre", "kilometres", "kilometer", "kilometers")
        put(Unit("length", 0.01, "centimetres"), "cm", "centimetre", "centimetres", "centimeter", "centimeters")
        put(Unit("length", 0.001, "millimetres"), "mm", "millimetre", "millimetres", "millimeter", "millimeters")
        put(Unit("length", 1609.344, "miles"), "mile", "miles", "mi")
        put(Unit("length", 0.9144, "yards"), "yard", "yards", "yd")
        put(Unit("length", 0.3048, "feet"), "ft", "foot", "feet")
        put(Unit("length", 0.0254, "inches"), "in", "inch", "inches")

        // mass, base kilogram
        put(Unit("mass", 1.0, "kilograms"), "kg", "kilo", "kilos", "kilogram", "kilograms")
        put(Unit("mass", 0.001, "grams"), "g", "gram", "grams")
        put(Unit("mass", 0.45359237, "pounds"), "lb", "lbs", "pound", "pounds")
        put(Unit("mass", 0.0283495, "ounces"), "oz", "ounce", "ounces")
        put(Unit("mass", 1000.0, "tonnes"), "tonne", "tonnes", "ton", "tons")

        // volume, base litre
        put(Unit("volume", 1.0, "litres"), "l", "litre", "litres", "liter", "liters")
        put(Unit("volume", 0.001, "millilitres"), "ml", "millilitre", "millilitres", "milliliter", "milliliters")
        put(Unit("volume", 3.785411784, "gallons"), "gallon", "gallons", "gal")
        put(Unit("volume", 0.473176, "pints"), "pint", "pints")

        // time, base second
        put(Unit("time", 1.0, "seconds"), "s", "sec", "secs", "second", "seconds")
        put(Unit("time", 60.0, "minutes"), "min", "mins", "minute", "minutes")
        put(Unit("time", 3600.0, "hours"), "h", "hr", "hrs", "hour", "hours")
        put(Unit("time", 86400.0, "days"), "day", "days")
        put(Unit("time", 604800.0, "weeks"), "week", "weeks")

        // digital, base byte
        put(Unit("data", 1.0, "bytes"), "b", "byte", "bytes")
        put(Unit("data", 1024.0, "kilobytes"), "kb", "kilobyte", "kilobytes")
        put(Unit("data", 1048576.0, "megabytes"), "mb", "megabyte", "megabytes")
        put(Unit("data", 1073741824.0, "gigabytes"), "gb", "gigabyte", "gigabytes")
        put(Unit("data", 1099511627776.0, "terabytes"), "tb", "terabyte", "terabytes")
    }

    private val conversionPattern =
        Regex("^(?:convert\\s+)?(-?[\\d.]+)\\s*([a-z°]+)\\s+(?:to|in|into|as)\\s+([a-z°]+)$")

    private fun conversion(text: String): Result? {
        val m = conversionPattern.find(text) ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2]
        val to = m.groupValues[3]

        // Temperature isn't a simple ratio, so it's handled separately rather than
        // being forced into the per-base scheme.
        temperature(amount, from, to)?.let { return it }

        val source = units[from] ?: return null
        val target = units[to] ?: return null
        if (source.family != target.family) return null

        val value = amount * source.perBase / target.perBase
        return Result("${format(amount)} ${source.canonical} in ${target.canonical}", value)
    }

    private fun temperature(amount: Double, from: String, to: String): Result? {
        fun kind(u: String) = when (u) {
            "c", "°c", "celsius", "centigrade" -> "c"
            "f", "°f", "fahrenheit" -> "f"
            "k", "kelvin" -> "k"
            else -> null
        }
        val a = kind(from) ?: return null
        val b = kind(to) ?: return null

        val celsius = when (a) {
            "c" -> amount
            "f" -> (amount - 32) * 5.0 / 9.0
            else -> amount - 273.15
        }
        val value = when (b) {
            "c" -> celsius
            "f" -> celsius * 9.0 / 5.0 + 32
            else -> celsius + 273.15
        }
        return Result("${format(amount)}°${a.uppercase()} in °${b.uppercase()}", value)
    }

    // ------------------------------------------------------------- arithmetic

    /** Only strings that are unambiguously an expression — otherwise the model gets it. */
    private val looksLikeExpression = Regex("^[-+*/^().\\d\\s]+$")

    private fun arithmetic(text: String): Result? {
        val expr = text.removePrefix("=").trim()
        if (!looksLikeExpression.matches(expr)) return null
        // A bare number is not a calculation; answering "7" with "7" is noise.
        if (!expr.any { it in "+-*/^" }) return null
        // A lone leading minus is a negative number, not an operation.
        if (expr.count { it in "+*/^" } == 0 && expr.trimStart().startsWith("-")) return null

        val value = runCatching { Parser(expr).parse() }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        return Result(expr, value)
    }

    /**
     * Recursive descent over the standard precedence ladder. Small enough to be
     * obviously correct, which is the entire reason for doing this locally.
     */
    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Double {
            val value = expression()
            skipSpace()
            // Trailing junk means we misread the input; better to decline than to
            // return a number derived from half of it.
            if (pos != src.length) throw IllegalArgumentException("unparsed remainder")
            return value
        }

        private fun expression(): Double {
            var left = term()
            while (true) {
                skipSpace()
                when (peek()) {
                    '+' -> { pos++; left += term() }
                    '-' -> { pos++; left -= term() }
                    else -> return left
                }
            }
        }

        private fun term(): Double {
            var left = power()
            while (true) {
                skipSpace()
                when (peek()) {
                    '*' -> { pos++; left *= power() }
                    '/' -> {
                        pos++
                        val divisor = power()
                        if (divisor == 0.0) throw ArithmeticException("divide by zero")
                        left /= divisor
                    }
                    else -> return left
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            skipSpace()
            // Right-associative: 2^3^2 is 2^(3^2).
            return if (peek() == '^') {
                pos++
                base.pow(power())
            } else {
                base
            }
        }

        private fun unary(): Double {
            skipSpace()
            return when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> atom()
            }
        }

        private fun atom(): Double {
            skipSpace()
            if (peek() == '(') {
                pos++
                val value = expression()
                skipSpace()
                if (peek() != ')') throw IllegalArgumentException("unbalanced parenthesis")
                pos++
                return value
            }
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("expected a number")
            return src.substring(start, pos).toDouble()
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null
    }

    // ---------------------------------------------------------------- output

    /**
     * Rounds off floating-point dust before display.
     *
     * 0.1 + 0.2 is 0.30000000000000004 in binary floating point, and showing that
     * would make correct arithmetic look broken. Ten significant decimals is well
     * inside double precision and past anything a person asked for out loud.
     */
    private fun format(value: Double): String {
        if (!value.isFinite()) return value.toString()
        val rounded = (value * 1e10).roundToLong() / 1e10
        if (abs(rounded) < 1e15 && rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
        return rounded.toString().trimEnd('0').trimEnd('.')
    }
}
