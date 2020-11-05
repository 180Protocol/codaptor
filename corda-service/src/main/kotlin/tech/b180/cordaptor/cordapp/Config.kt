package tech.b180.cordaptor.cordapp

import com.typesafe.config.ConfigException
import net.corda.core.cordapp.CordappConfig
import tech.b180.cordaptor.kernel.Config
import tech.b180.cordaptor.kernel.ConfigPath
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Implements microkernel's [Config] interface by using CorDapp configuration available within Corda,
 * but referring to the fallback configuration to lookup missing values.
 */
class CordappConfigWithFallback(
    private val cordappConfig: CordappConfig,
    private val fallback: Config?,
    private val pathPrefix: String = ""
) : Config {

  companion object {

    // adapted from com.typesafe.config.impl.SimpleConfig.parseDuration(), which is package-private
    fun parseDuration(str: String, path: ConfigPath): Duration {
      val trimmedString = str.trim()
      val unitString = getUnits(trimmedString).trim()
      val numberString = trimmedString.substring(0, trimmedString.length - unitString.length).trim()

      val units = when (unitString) {
        "", "ms", "millis", "milliseconds" -> ChronoUnit.MILLIS
        "us", "micros", "microseconds" -> ChronoUnit.MICROS
        "ns", "nanos", "nanoseconds" -> ChronoUnit.NANOS
        "d", "day", "days" -> ChronoUnit.DAYS
        "h", "hour", "hours" -> ChronoUnit.HOURS
        "s", "second", "seconds" -> ChronoUnit.SECONDS
        "m", "minute", "minutes" -> ChronoUnit.MINUTES
        else -> throw ConfigException.BadValue(path, "Could not parse time unit '$unitString' " +
            "(try ns, us, ms, s, m, h, d)")
      }

      return try {
        // if the string is purely digits, parse as an integer to avoid
        // possible precision loss, otherwise as a double.
        if (numberString.matches(Regex("[+-]?[0-9]+"))) {
          Duration.of(numberString.toLong(), units)
        } else {
          val nanosInUnit = Duration.of(1, units).toNanos()
          Duration.of((numberString.toDouble() * nanosInUnit).toLong(), ChronoUnit.NANOS)
        }
      } catch (e: NumberFormatException) {
        throw ConfigException.BadValue(path, "Could not parse duration number '$numberString'")
      }
    }

    // adapted from com.typesafe.config.impl.SimpleConfig.parse(), which is package-private
    fun parseBytesSize(str: String, path: ConfigPath): Long {
      val trimmedString = str.trim()
      val unitString = getUnits(trimmedString)
      val numberString = trimmedString.substring(0, trimmedString.length - unitString.length).trim()

      val units = unitsMap[unitString]
          ?: throw ConfigException.BadValue(path, "Could not parse size-in-bytes unit '$unitString' " +
              "(try k, K, kB, KiB, kilobytes, kibibytes)")

      return try {
        val result: BigInteger
        // if the string is purely digits, parse as an integer to avoid
        // possible precision loss; otherwise as a double.
        result = if (numberString.matches(Regex("[0-9]+"))) {
          units.bytes.multiply(BigInteger(numberString))
        } else {
          val resultDecimal = BigDecimal(units.bytes).multiply(BigDecimal(numberString))
          resultDecimal.toBigInteger()
        }
        if (result.bitLength() < 64)
          result.toLong()
        else
          throw ConfigException.BadValue(path,
              "size-in-bytes value is out of range for a 64-bit long: '$trimmedString'")
      } catch (e: NumberFormatException) {
        throw ConfigException.BadValue(path, "Could not parse size-in-bytes number '$numberString'")
      }
    }

    // we are using commas as delimiter unless they are prefixed by backward slash
    fun parseStringsList(str: String): List<String> {
      val trimmedString = str.trim()
      return trimmedString.split(",")
    }

    private fun getUnits(s: String): String {
      var i = s.length - 1
      while (i >= 0) {
        val c = s[i]
        if (!Character.isLetter(c)) break
        i -= 1
      }
      return s.substring(i + 1)
    }

    private val unitsMap = makeUnitsMap()

    private fun makeUnitsMap(): Map<String, MemoryUnit> {
      val map: MutableMap<String, MemoryUnit> = HashMap()
      for (unit in MemoryUnit.values()) {
        map[unit.prefix + "byte"] = unit
        map[unit.prefix + "bytes"] = unit
        if (unit.prefix.isEmpty()) {
          map["b"] = unit
          map["B"] = unit
          map[""] = unit // no unit specified means bytes
        } else {
          val first = unit.prefix.substring(0, 1)
          val firstUpper = first.toUpperCase()
          if (unit.powerOf == 1024) {
            map[first] = unit // 512m
            map[firstUpper] = unit // 512M
            map[firstUpper + "i"] = unit // 512Mi
            map[firstUpper + "iB"] = unit // 512MiB
          } else if (unit.powerOf == 1000) {
            if (unit.power == 1) {
              map[first + "B"] = unit // 512kB
            } else {
              map[firstUpper + "B"] = unit // 512MB
            }
          } else {
            throw RuntimeException("broken MemoryUnit enum")
          }
        }
      }
      return map
    }
  }

  // Adapted from com.typesafe.config.impl.SimpleConfig.MemoryUnit
  private enum class MemoryUnit(val prefix: String, val powerOf: Int, val power: Int) {
    BYTES("", 1024, 0),
    KILOBYTES("kilo", 1000, 1),
    MEGABYTES("mega", 1000, 2),
    GIGABYTES("giga", 1000, 3),
    TERABYTES("tera", 1000, 4),
    PETABYTES("peta", 1000, 5),
    EXABYTES("exa", 1000, 6),
    ZETTABYTES("zetta", 1000, 7),
    YOTTABYTES("yotta", 1000, 8),

    KIBIBYTES("kibi", 1024, 1),
    MEBIBYTES("mebi", 1024, 2),
    GIBIBYTES("gibi", 1024, 3),
    TEBIBYTES("tebi", 1024, 4),
    PEBIBYTES("pebi", 1024, 5),
    EXBIBYTES("exbi", 1024, 6),
    ZEBIBYTES("zebi", 1024, 7),
    YOBIBYTES("yobi", 1024, 8);

    var bytes: BigInteger = BigInteger.valueOf(powerOf.toLong()).pow(power)
  }

  override fun pathExists(path: ConfigPath): Boolean {
    return if (cordappConfig.exists(pathPrefix + path)) {
      true
    } else {
      fallback?.pathExists(path) ?: false
    }
  }

  override fun getSubtree(path: ConfigPath): Config {
    val prefixedPath = pathPrefix + path
    return if (cordappConfig.exists(prefixedPath)) {
      val subtreeFallback = if (fallback?.pathExists(path) == true) fallback.getSubtree(path) else null
      return CordappConfigWithFallback(cordappConfig, subtreeFallback, "$prefixedPath.")
    } else {
      // nothing in the CorDapp config, so the subtree would be provided entirely by the fallback
      fallback?.getSubtree(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getString(path: ConfigPath): String {
    return if (cordappConfig.exists(pathPrefix + path)) {
      return cordappConfig.getString(pathPrefix + path)
    } else {
      fallback?.getString(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getStringsList(path: ConfigPath): List<String> {
    return if (cordappConfig.exists(pathPrefix + path)) {
      parseStringsList(cordappConfig.getString(pathPrefix + path))
    } else {
      fallback?.getStringsList(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getDuration(path: ConfigPath): Duration {
    val prefixedPath = pathPrefix + path
    return if (cordappConfig.exists(prefixedPath)) {
      parseDuration(cordappConfig.getString(prefixedPath), prefixedPath)
    } else {
      fallback?.getDuration(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getInt(path: ConfigPath): Int {
    return if (cordappConfig.exists(pathPrefix + path)) {
      return cordappConfig.getInt(pathPrefix + path)
    } else {
      fallback?.getInt(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getLong(path: ConfigPath): Long {
    return if (cordappConfig.exists(pathPrefix + path)) {
      return cordappConfig.getLong(pathPrefix + path)
    } else {
      fallback?.getLong(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getDouble(path: ConfigPath): Double {
    return if (cordappConfig.exists(pathPrefix + path)) {
      return cordappConfig.getDouble(pathPrefix + path)
    } else {
      fallback?.getDouble(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getBytesSize(path: ConfigPath): Long {
    val prefixedPath = pathPrefix + path
    return if (cordappConfig.exists(prefixedPath)) {
      return parseBytesSize(cordappConfig.getString(prefixedPath), prefixedPath)
    } else {
      fallback?.getBytesSize(path) ?: throw ConfigException.Missing(path)
    }
  }

  override fun getBoolean(path: ConfigPath): Boolean {
    return if (cordappConfig.exists(pathPrefix + path)) {
      return cordappConfig.getBoolean(pathPrefix + path)
    } else {
      fallback?.getBoolean(path) ?: throw ConfigException.Missing(path)
    }
  }
}