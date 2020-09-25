# B180 Cordaptor

## Modules list

* **kernel** - Microkernel responsible for locating other modules in the classpath, managing component lifecycles,
providing configuration, and resolving inter-component dependencies.
* **corda-service** - Responsible for instantiating Cordaptor as a Corda service within Corda node JVM and allowing
other modules to access the functionality of the node's internals, e.g. querying the vault or invoking flows.
* **corda-rpc-client** - Responsible for connecting with a Corda node via Corda RPC and managing this connection,
whilst also providing other modules with access to the functionality of the node.
* **corda-bindings** - Common definitions and logic allowing the functionality of Corda node to be accessed by
other modules regardless of the deployment model.
* **rest-endpoint** - Exposes an HTTP-based REST API endpoint based on embedded Jetty web server. Provides mechanisms
for securing the API endpoint.

## Bundles

* **bundle-rest-embedded** -- contains all modules required to deploy a REST API endpoint as part of a CorDapp
inside a Corda node.
* **bundle-rest-standalone** -- contains all modules required to deploy a REST API endpoint as a standalone process
interacting with a Corda node via the RPC link.
