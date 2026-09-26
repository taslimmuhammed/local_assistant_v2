package com.local.assistant.memory.tools

import java.util.Locale

/**
 * Unit conversions for the calculate tool: "5 miles in km", "98.6 f to c", "1200 sq ft in sq m",
 * "2 cups in ml". The amount may itself be a sum ("3*1.5 kg in lb"). Units convert only within
 * their kind; temperatures, which don't start at zero, are handled apart.
 */
object Units {

    enum class Kind { LENGTH, MASS, VOLUME, AREA, SPEED, TIME, DATA, TEMPERATURE }

    data class Unit(val name: String, val kind: Kind, val toBase: Double)

    /** "5 miles in km" → the amount's expression, and the two units; null if it isn't a conversion. */
    fun parse(text: String): Triple<String, Unit, Unit>? {
        val lower = text.lowercase(Locale.ROOT).trim().trimEnd('?', '.')
        val match = SEPARATOR.findAll(lower).lastOrNull() ?: return null
        val left = lower.substring(0, match.range.first).trim()
        val to = find(lower.substring(match.range.last + 1).trim()) ?: return null
        // The longest unit name the left side ends with: "sq ft" before "ft".
        val (fromName, from) = ALIASES.entries
            .filter { (alias, _) -> left.endsWith(alias) && (left.length == alias.length || !left[left.length - alias.length - 1].isLetter()) }
            .maxByOrNull { it.key.length }
            ?.let { it.key to it.value } ?: return null
        val amount = left.dropLast(fromName.length).trim().ifEmpty { "1" }
        if (from.kind != to.kind) throw Calculator.Error("${from.name} and ${to.name} measure different things.")
        return Triple(amount, from, to)
    }

    fun convert(value: Double, from: Unit, to: Unit): Double =
        if (from.kind == Kind.TEMPERATURE) fromCelsius(toCelsius(value, from), to) else value * from.toBase / to.toBase

    private fun find(name: String): Unit? = ALIASES[name.trim()]

    private fun toCelsius(value: Double, unit: Unit): Double = when (unit.name) {
        "°F" -> (value - 32) * 5 / 9
        "K" -> value - 273.15
        else -> value
    }

    private fun fromCelsius(value: Double, unit: Unit): Double = when (unit.name) {
        "°F" -> value * 9 / 5 + 32
        "K" -> value + 273.15
        else -> value
    }

    /** " in ", " to ", " into ", " = " between the amount and the unit wanted. */
    private val SEPARATOR = Regex("\\s(?:in|to|into|as)(?=\\s)|\\s*=\\s*")

    private val ALIASES: Map<String, Unit> = buildMap {
        fun unit(name: String, kind: Kind, toBase: Double, vararg aliases: String) {
            val unit = Unit(name, kind, toBase)
            (aliases.toList() + name.lowercase(Locale.ROOT)).forEach { put(it, unit) }
        }
        unit("mm", Kind.LENGTH, 0.001, "millimetre", "millimetres", "millimeter", "millimeters")
        unit("cm", Kind.LENGTH, 0.01, "centimetre", "centimetres", "centimeter", "centimeters")
        unit("m", Kind.LENGTH, 1.0, "metre", "metres", "meter", "meters")
        unit("km", Kind.LENGTH, 1000.0, "kms", "kilometre", "kilometres", "kilometer", "kilometers")
        unit("in", Kind.LENGTH, 0.0254, "inch", "inches")
        unit("ft", Kind.LENGTH, 0.3048, "foot", "feet")
        unit("yd", Kind.LENGTH, 0.9144, "yard", "yards")
        unit("mi", Kind.LENGTH, 1609.344, "mile", "miles")

        unit("mg", Kind.MASS, 1e-6, "milligram", "milligrams")
        unit("g", Kind.MASS, 0.001, "gm", "gms", "gram", "grams")
        unit("kg", Kind.MASS, 1.0, "kgs", "kilo", "kilos", "kilogram", "kilograms")
        unit("t", Kind.MASS, 1000.0, "tonne", "tonnes", "metric ton", "metric tons")
        unit("quintal", Kind.MASS, 100.0, "quintals")
        unit("oz", Kind.MASS, 0.028349523125, "ounce", "ounces")
        unit("lb", Kind.MASS, 0.45359237, "lbs", "pound", "pounds")
        unit("st", Kind.MASS, 6.35029318, "stone", "stones")
        unit("tola", Kind.MASS, 0.0116638125, "tolas")

        unit("ml", Kind.VOLUME, 0.001, "millilitre", "millilitres", "milliliter", "milliliters")
        unit("L", Kind.VOLUME, 1.0, "l", "litre", "litres", "liter", "liters", "ltr")
        unit("tsp", Kind.VOLUME, 0.00492892159375, "teaspoon", "teaspoons")
        unit("tbsp", Kind.VOLUME, 0.01478676478125, "tablespoon", "tablespoons")
        unit("cup", Kind.VOLUME, 0.2365882365, "cups")
        unit("fl oz", Kind.VOLUME, 0.0295735295625, "fluid ounce", "fluid ounces")
        unit("pint", Kind.VOLUME, 0.473176473, "pints")
        unit("gallon", Kind.VOLUME, 3.785411784, "gallons", "gal")

        unit("sq m", Kind.AREA, 1.0, "m2", "m²", "sqm", "square metre", "square metres", "square meter", "square meters")
        unit("sq ft", Kind.AREA, 0.09290304, "sqft", "ft2", "ft²", "square foot", "square feet")
        unit("sq yd", Kind.AREA, 0.83612736, "square yard", "square yards", "gaj")
        unit("sq km", Kind.AREA, 1e6, "km2", "km²", "square kilometre", "square kilometres", "square kilometer", "square kilometers")
        unit("acre", Kind.AREA, 4046.8564224, "acres")
        unit("hectare", Kind.AREA, 10_000.0, "hectares", "ha")
        unit("cent", Kind.AREA, 40.468564224, "cents")

        unit("km/h", Kind.SPEED, 1 / 3.6, "kmph", "kph", "kmh", "kilometres per hour", "kilometers per hour")
        unit("mph", Kind.SPEED, 0.44704, "miles per hour")
        unit("m/s", Kind.SPEED, 1.0, "metres per second", "meters per second")
        unit("knot", Kind.SPEED, 0.514444, "knots")

        unit("sec", Kind.TIME, 1.0, "s", "second", "seconds", "secs")
        unit("min", Kind.TIME, 60.0, "mins", "minute", "minutes")
        unit("hour", Kind.TIME, 3600.0, "h", "hr", "hrs", "hours")
        unit("day", Kind.TIME, 86_400.0, "days")
        unit("week", Kind.TIME, 604_800.0, "weeks")

        unit("KB", Kind.DATA, 1e3, "kilobyte", "kilobytes")
        unit("MB", Kind.DATA, 1e6, "megabyte", "megabytes")
        unit("GB", Kind.DATA, 1e9, "gigabyte", "gigabytes")
        unit("TB", Kind.DATA, 1e12, "terabyte", "terabytes")

        unit("°C", Kind.TEMPERATURE, 1.0, "c", "celsius", "centigrade", "degrees c", "degree c", "deg c")
        unit("°F", Kind.TEMPERATURE, 1.0, "f", "fahrenheit", "degrees f", "degree f", "deg f")
        unit("K", Kind.TEMPERATURE, 1.0, "k", "kelvin")
    }
}
