package tech.b180.ref_cordapp

import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.declaredField
import net.corda.core.node.AppServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.SerializerFactoryBuilder
import net.corda.serialization.internal.carpenter.ClassCarpenterImpl
import net.corda.serialization.internal.model.BaseLocalTypes
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalTypeLookup
import net.corda.serialization.internal.model.LocalTypeModelConfiguration
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Type
import kotlin.reflect.jvm.javaGetter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CordaInternalApiTest {

  private val network = MockNetwork(
      MockNetworkParameters(
          cordappsForAllNodes = listOf(
              TestCordapp.findCordapp("tech.b180.ref_cordapp")
          )
      )
  )

  private val node = network.createUnstartedNode(MockNodeParameters(legalName = CordaX500Name.parse("O=Org,L=London,C=GB")))

  @Before
  fun startNetwork() {
    node.installCordaService(ApiIntrospectionService::class.java)
    node.start()

    network.runNetwork()
  }

  @After
  fun stopNetwork() {
    network.stopNodes()
  }

  @Test
  fun `can access cordapps list`() {
    assertNotNull(ApiIntrospectionService.serviceHubInstance, "Corda service hub must be provided")

    val hub = ApiIntrospectionService.serviceHubInstance!!

    // AppServiceHubImpl is declared internal, which means we cannot access it directly
    val appServiceHubImplClass = Class.forName("net.corda.node.internal.AppServiceHubImpl") as Class<AppServiceHub>

    assertEquals(appServiceHubImplClass, hub::class.java, "Corda service can access internal node API via app service hub")

    // AppServiceHubImpl delegates all of the implementation of ServiceHub to a field,
    // which contains an instance of ServiceHubInternalImpl, which allows us to obtain a CordappProvider
    val serviceHubField = appServiceHubImplClass.declaredFields.find { it.name == "serviceHub" }!!
    serviceHubField.isAccessible = true
    val serviceHubImpl = serviceHubField.get(hub) as ServiceHubInternal;

    val cordapps = serviceHubImpl.cordappProvider.cordapps.filter { it.info.shortName != "corda-core" }
    assertEquals(1, cordapps.size, "Internal API is accessible via service hub instance")
    assertEquals("cordaptor-reference", cordapps[0].info.shortName, "Reference CorDapp is available")

    val cordapp = cordapps[0]
    assertEquals(TrivialContract::class.qualifiedName, cordapp.contractClassNames[0])

    val factory = SerializerFactoryBuilder.build(whitelist = AllWhitelist, classCarpenter = ClassCarpenterImpl(AllWhitelist))
    val typeInfo = factory.getTypeInformation(cordapp.contractClassNames[0])

    assertNotNull(typeInfo, "Can access local type information")
  }
}

@CordaService
class ApiIntrospectionService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

  init {
    serviceHubInstance = serviceHub
  }

  companion object {
    var serviceHubInstance: AppServiceHub? = null
  }
}