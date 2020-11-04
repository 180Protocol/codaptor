import net.corda.core.cordapp.CordappConfig
import tech.b180.cordaptor.cordapp.CordappConfigWithFallback
import tech.b180.cordaptor.kernel.TypesafeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {

  @Test
  fun `test cordapp config overriding fallback config`() {
    val fallback = TypesafeConfig.loadFrom("test-fallback.conf")
    val cordappConfig = MockCordappConfig(TypesafeConfig.loadFrom("test-cordapp.conf"))

    val testConfig = CordappConfigWithFallback(cordappConfig, fallback)

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
