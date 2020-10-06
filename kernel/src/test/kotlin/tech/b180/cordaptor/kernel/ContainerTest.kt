package tech.b180.cordaptor.kernel

import org.junit.BeforeClass
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.test.*

class ContainerTest {

  companion object {

    private lateinit var containerInstance: Container

    @BeforeClass @JvmStatic
    fun `init`() {
      containerInstance = Container {
        module(override = true) {
          single { WrapperComponent(get()) }
          single<TestService>(named("override")) {
            OuterTestServiceImpl(get(named("override")))
          } bind Marker::class

          // reexporting an existing definition to emulate disabled conditional override
          single<TestService>(named("no-override")) {
            get(InnerTestService::class, named("no-override"))
          } bind Marker::class
        }
      }
    }
  }

  @Test
  fun `test module provider locator`() {
    assertNotNull(containerInstance, "Container must have been created")

    val component = containerInstance.get(TestComponent::class)
    assertNotNull(component)

    val wrapper = containerInstance.get(WrapperComponent::class)
    assertNotNull(wrapper)
    assertSame(wrapper.testComponent, component)
  }

  @Test
  fun `test property access`() {
    val component = containerInstance.get(TestComponent::class)

    // values are defined in /koin.properties
    assertEquals("ABC", component.stringValue)
    assertEquals(true, component.booleanValue)
    assertEquals(123, component.integerValue)
  }

  @Test
  fun `test implementation overrides`() {
    val service1 = containerInstance.get(TestService::class, named("override"))
    assertEquals("Outer work(Inner work)", service1.doWork())

    val service2 = containerInstance.get(TestService::class, named("no-override"))
    assertTrue(service2 is InnerTestService)
    assertEquals("Inner work", service2.doWork())
  }

  @Test
  fun `test markers`() {
    val markers = containerInstance.getAll(Marker::class)
    assertEquals(4, markers.size)

    // unfortunate side effect of Koin library
    // the test makes sure that if the behaviour changes, it gets captured
    val nonOverridenService = containerInstance.get(TestService::class, named("no-override"))
    assertEquals(2, markers.count { it == nonOverridenService }, "Reexported object is bound twice")
  }
}

interface Marker

class TestComponent(
    val stringValue: String,
    val booleanValue: Boolean,
    val integerValue: Int
) : CordaptorComponent

class WrapperComponent(val testComponent: TestComponent) : CordaptorComponent

interface TestService : Marker {
  fun doWork(): String
}

interface InnerTestService : TestService

class InnerTestServiceImpl : InnerTestService {
  override fun doWork() = "Inner work"
}

class OuterTestServiceImpl(private val delegate: InnerTestService) : TestService {
  override fun doWork() = "Outer work(${delegate.doWork()})"
}

/**
 * This class is instantiated by [Container] using [java.util.ServiceLoader]
 * because it is declared in META-INF/services
 */
@Suppress("UNUSED")
class ContainerTestModuleProvider : ModuleProvider {
  override fun provideModule(settings: BootstrapSettings): Module = module {
    single { TestComponent(
        getProperty("stringValue"),
        getBooleanProperty("booleanValue"),
        getIntegerProperty("integerValue")
    ) }
    single<InnerTestService>(named("override")) { InnerTestServiceImpl() } bind TestService::class bind Marker::class
    single<InnerTestService>(named("no-override")) { InnerTestServiceImpl() } bind TestService::class bind Marker::class
  }

  override val salience = ModuleProvider.INNER_MODULE_SALIENCE
}
