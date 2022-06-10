package tech.b180.cordaptor.corda

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.cordapp.Cordapp
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import tech.b180.cordaptor.kernel.loggerFor
import java.io.File
import java.io.InputStreamReader
import java.util.jar.JarFile
import kotlin.reflect.KClass

/**
 * Common node catalog builder logic using Corda introspection API.
 * This class is also responsible for identifying anomalies and reporting them.
 */
class CordappInfoBuilder(
    private val cordapps: List<Cordapp>,
    private val isEmbedded: Boolean
) {

  companion object {
    private val logger = loggerFor<CordappInfoBuilder>()
  }

  fun build(): List<CordappInfo> {
    return cordapps.map { cordapp ->
      logger.info("Found CordApp ${cordapp.info} at ${cordapp.jarPath}")

      val settings = buildCordappSettings(cordapp)

      CordappInfo(
          shortName = settings.urlPath,
          jarHash = cordapp.jarHash,
          jarURL = cordapp.jarPath,
          flows = when(isEmbedded) {
            true -> {
              cordapp.serviceFlows
                .filter {
                  flowClass ->
                  if (!FlowLogic::class.java.isAssignableFrom(flowClass)) {
                    throw AssertionError(
                      "Class ${flowClass.canonicalName} " +
                              "was identified as a flow by Corda, but cannot be cast to FlowLogic<*>"
                    )
                  }
                  val canStartByService = flowClass.getAnnotation(StartableByService::class.java) != null
                  val canStartByRPC = flowClass.getAnnotation(StartableByRPC::class.java) != null
                  // ignore the flow as it is not available to Corda service
                  // and provide further information in the log to help with troubleshooting
                  if (!canStartByService) {
                    if (canStartByRPC) {
                      logger.warn(
                        "Flow class ${flowClass.canonicalName} can be started by RPC, " +
                                "but is not available to Cordaptor running as a service within the node. " +
                                "Annotate the flow class with @StartableByService to make it available"
                      )
                    }
                    logger.debug(
                      "Ignoring flow class {} as it is not available to Corda services",
                      flowClass.canonicalName
                    )
                  }
                  canStartByService
                }
            }
              false -> {
              cordapp.rpcFlows
                .filter {
                  flowClass ->
                  if (!FlowLogic::class.java.isAssignableFrom(flowClass)) {
                    throw AssertionError(
                      "Class ${flowClass.canonicalName} " +
                              "was identified as a flow by Corda, but cannot be cast to FlowLogic<*>"
                    )
                  }
                  val canStartByService = flowClass.getAnnotation(StartableByService::class.java) != null
                  val canStartByRPC = flowClass.getAnnotation(StartableByRPC::class.java) != null
                  // ignore the flow as it is not available to Corda RPC
                  // and provide further information in the log to help with troubleshooting
                  if (!canStartByRPC) {
                    if (canStartByService) {
                      logger.warn(
                        "Flow class ${flowClass.canonicalName} can be started by a Corda service, " +
                                "but is not available to Cordaptor running as a standalone gateway. " +
                                "Annotate the flow class with @StartableByRPC to make it available"
                      )
                    }
                    logger.debug(
                      "Ignoring flow class {} as it is not available to Corda RPC clients",
                      flowClass.canonicalName
                    )
                  }
                  canStartByRPC
                }
            }
          }.map { flowClass ->
            @Suppress("UNCHECKED_CAST")
            val flowKClass = flowClass.kotlin as KClass<out FlowLogic<Any>>
            val flowResultClass = determineFlowResultClass(flowKClass)

            logger.debug("Registering flow class {} returning instances of {}",
                flowClass.canonicalName, flowResultClass.qualifiedName)

            CordappFlowInfo(flowClass = flowKClass, flowResultClass = flowResultClass)
          },
          contractStates = cordapp.cordappClasses
              // filtering out SDK classes that could be picked up by the introspecting scanner
              .filter { pkg -> pkg.contains("state") }
              .map { Class.forName(it) }
              .filter { clazz ->
                ContractState::class.java.isAssignableFrom(clazz).also {
                  // this class will be registered
                  if (!it) {
                    logger.debug("Cordapp class {} does not implement ContractState interface", clazz.canonicalName)
                  }
                }
              }
              .map { clazz ->
                val contractAnnotation = clazz.getAnnotation(BelongsToContract::class.java)
                if (contractAnnotation == null) {
                  logger.warn("Contract state class ${clazz.canonicalName} is not annotated with @BelongsToContract")
                }

                @Suppress("UNCHECKED_CAST")
                val stateClass = clazz.kotlin as KClass<out ContractState>

                logger.debug("Registering contract state class {} managed by contract {}",
                    clazz.canonicalName, contractAnnotation?.value?.qualifiedName ?: "(no annotation)")

                CordappContractStateInfo(stateClass = stateClass)
              },

          mappedSchemas = cordapp.customSchemas
      )
    }
  }

  private fun buildCordappSettings(cordapp: Cordapp): CordappSettings {
    val jarFile = File(cordapp.jarPath.path)
    if (!jarFile.exists() || !jarFile.canRead()) {
      throw AssertionError("CorDapp JAR file is not accessible: $jarFile")
    }
    val jar = JarFile(jarFile.absoluteFile, false)
    val config = try {
      jar.use {
        val entry = jar.getJarEntry("META-INF/cordaptor.conf")
        if (entry != null) {
          val config = it.getInputStream(entry).use { entryStream ->
            InputStreamReader(entryStream).readText()
          }
          ConfigFactory.parseString(config).resolve()
        } else {
          logger.debug("CorDapp JAR {} does not contain META-INF/cordaptor.conf entry, " +
              "using default settings", cordapp.name)
          null
        }
      }
    } catch (e: Exception) {
      logger.error("Error reading META-INF/cordaptor.conf of ${cordapp.name}, using default settings", e)
      null
    } ?: return CordappSettings.default(cordapp)

    return CordappSettings(config, cordapp).also {
      logger.debug("Loaded CorDapp settings from {}!/META-INF/cordaptor.conf: {}",
          cordapp.jarPath, it)
    }
  }
}

/** Wrapper for  */
data class CordappSettings(
    val urlPath: String
) {

  companion object {
    fun default(cordapp: Cordapp) = CordappSettings(urlPath = cordapp.info.shortName)
  }

  constructor(config: Config, cordapp: Cordapp) : this(
      urlPath = if (config.hasPath("urlPath"))
        config.getString("urlPath")
      else
        cordapp.info.shortName
  )
}