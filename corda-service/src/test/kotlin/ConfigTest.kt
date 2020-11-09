import net.corda.core.cordapp.CordappConfig
import tech.b180.cordaptor.cordapp.CordappConfigWithFallback
import tech.b180.cordaptor.kernel.ConfigSecretsStore
import tech.b180.cordaptor.kernel.TypesafeConfig
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {

  private val fallback = TypesafeConfig.loadFrom("test-fallback.conf")
  private val cordappConfig = MockCordappConfig(TypesafeConfig.loadFrom("test-cordapp.conf"))
  private val testConfig = CordappConfigWithFallback(cordappConfig, fallback)

  @Test
  fun `test time unit parsing in CorDapp configs`() {
    assertEquals(Duration.ofNanos(1), testConfig.getDuration("units.duration1ns"))
    assertEquals(Duration.of(1, ChronoUnit.MICROS), testConfig.getDuration("units.duration1us"))
    assertEquals(Duration.ofMillis(1), testConfig.getDuration("units.duration1ms"))
    assertEquals(Duration.ofSeconds(1), testConfig.getDuration("units.duration1s"))
    assertEquals(Duration.ofMinutes(1), testConfig.getDuration("units.duration1m"))
    assertEquals(Duration.ofHours(1), testConfig.getDuration("units.duration1h"))
    assertEquals(Duration.ofDays(1), testConfig.getDuration("units.duration1d"))
  }

  @Test
  fun `test string lists parsing in CorDapp configs`() {
    assertEquals(listOf("ABC"), testConfig.getStringsList("lists.one"))
    assertEquals(listOf("ABC", "CDF"), testConfig.getStringsList("lists.two"))
    assertEquals(listOf("", "CDF", ""), testConfig.getStringsList("lists.empty"))
  }

  @Test
  fun `test byte size units parsing in CorDapp configs`() {
    assertEquals(1, testConfig.getBytesSize("units.size1b"))

    assertEquals(1024, testConfig.getBytesSize("units.size1Ki"))
    assertEquals(1024 * 1024, testConfig.getBytesSize("units.size1Mi"))
    assertEquals(1024 * 1024 * 1024, testConfig.getBytesSize("units.size1Gi"))

    assertEquals(1000, testConfig.getBytesSize("units.size1k"))
    assertEquals(1000 * 1000, testConfig.getBytesSize("units.size1m"))
    assertEquals(1000 * 1000 * 1000, testConfig.getBytesSize("units.size1g"))
  }

  @Test
  fun `test cordapp config overriding fallback config`() {
    assertTrue(testConfig.pathExists("fallbackOnly"))
    assertTrue(testConfig.pathExists("fallbackOnly.int"))
    assertEquals(1, testConfig.getInt("fallbackOnly.int"))
    assertEquals(1, testConfig.getSubtree("fallbackOnly").getInt("int"))

    assertTrue(testConfig.pathExists("configOnly"))
    assertTrue(testConfig.pathExists("configOnly.int"))
    assertEquals(3, testConfig.getInt("configOnly.int"))
    assertEquals(3, testConfig.getSubtree("configOnly").getInt("int"))

    assertTrue(testConfig.pathExists("cordappOverride"))
    assertTrue(testConfig.pathExists("cordappOverride.subTree"))
    assertTrue(testConfig.pathExists("cordappOverride.subTree.flag"))
    assertTrue(testConfig.getSubtree("cordappOverride").pathExists("subTree"))
    assertTrue(testConfig.getSubtree("cordappOverride").pathExists("subTree.flag"))
    assertTrue(testConfig.getSubtree("cordappOverride").getSubtree("subTree").pathExists("flag"))
    assertTrue(testConfig.getSubtree("cordappOverride.subTree").pathExists("flag"))

    assertFalse(testConfig.getBoolean("cordappOverride.subTree.flag"))
    assertFalse(testConfig.getSubtree("cordappOverride").getBoolean("subTree.flag"))
    assertFalse(testConfig.getSubtree("cordappOverride.subTree").getBoolean("flag"))
    assertFalse(testConfig.getSubtree("cordappOverride").getSubtree("subTree").getBoolean("flag"))

    assertTrue(testConfig.pathExists("cordappOverride.cordappString"))
    assertTrue(testConfig.pathExists("cordappOverride.fallbackDouble"))
    assertTrue(testConfig.getSubtree("cordappOverride").pathExists("cordappString"))
    assertTrue(testConfig.getSubtree("cordappOverride").pathExists("fallbackDouble"))
    assertEquals("ABC", testConfig.getString("cordappOverride.cordappString"))
    assertEquals(1.2, testConfig.getDouble("cordappOverride.fallbackDouble"))
  }

  @Test
  fun `test secrets`() {
    assertEquals("secrets.string", testConfig.getStringSecret("secrets.string").id)
    assertEquals("secrets.string", testConfig.getSubtree("secrets").getStringSecret("string").id)

    assertEquals("Secret", ConfigSecretsStore(testConfig).useStringSecret("secrets.string") { String(it) })
  }
}

class MockCordappConfig(private val delegate: TypesafeConfig) : CordappConfig {

  override fun exists(path: String) = delegate.pathExists(path)
  override fun get(path: String): Any = throw NotImplementedError()
  override fun getBoolean(path: String) = delegate.getBoolean(path)
  override fun getDouble(path: String) = delegate.getDouble(path)
  override fun getFloat(path: String) = delegate.getDouble(path).toFloat()
  override fun getInt(path: String) = delegate.getInt(path)
  override fun getLong(path: String) = delegate.getLong(path)
  override fun getNumber(path: String) = delegate.getLong(path)
  override fun getString(path: String) = delegate.getString(path)
}
