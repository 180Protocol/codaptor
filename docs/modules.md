# B180 Cordaptor

## Modules list

* **kernel** - Microkernel responsible for locating other modules in the classpath, managing component lifecycles,
providing configuration, and resolving inter-component dependencies.
* **corda-service** - Responsible for instantiating Cordaptor as a Corda service within Corda node JVM and allowing
other modules to access the functionality of the node's internals, e.g. querying the vault or invoking flows.
* **corda-rpc-client** - Responsible for connecting with a Corda node via Corda RPC and managing this connection,
whilst also providing other modules with access to the functionality of the node.
* **corda-common** - Common definitions and logic allowing the functionality of Corda node to be accessed by
other modules regardless of the deployment model.
* **rest-endpoint** - Exposes an HTTP-based REST API endpoint based on embedded Jetty web server. Provides mechanisms
for securing the API endpoint. Implements WebSocket API endpoint for real-time interaction with Corda node.
* **reference-cordapp** - Implementation of a CorDapp used in the test suites. It is not intended to be included in
any Cordaptor bundle.
* **local-cache** - Uses local in-memory cache maintaining a replica of the vault to provide faster response time
for queries and protectes the node from spikes in queries. When deployed in a standalone Cordaptor
* **distributed-cache** - Implements distributed caching using Redis and Redisson library to allow vault queries
to be served without reaching the underlying Corda node. This module also allows Cordaptor to effectively work
in a cluster by providing a fault-tolerant websocket implementation through Redis as a pub/sub broker.

## Bundles

### **bundle-rest-embedded**
Contains all modules required to deploy a REST API endpoint as part of a CorDapp
inside a Corda node. This bundle can work with zero configuration and is mainly intended for local development
or low-stakes deployments where reduced operations overhead is more important than features of the standalone mode.

Includes the following modules:
* **kernel**
* **corda-common**
* **corda-service**
* **local-cache** (disabled by default)
* **rest-endpoint**

### **bundle-rest-standalone**
Contains all modules required to deploy a REST API endpoint as a single
standalone process interacting with a Corda node via the RPC link. This bundle allows better security for
the underlying Corda node, and provides some features improving service availability.

Includes the following modules:
* **kernel**
* **corda-common**
* **corda-rpc-client**
* **local-cache**
* **rest-endpoint**

### **bundle-rest-cluster**
Contains all modules required to deploy a REST API endpoint as a cluster of instances
which provide access to data from the underlying Corda node from a synchronized in-memory cache.

Includes the following modules:
* **kernel**
* **corda-common**
* **corda-rpc-client**
* **distributed-cache**
* **rest-endpoint**
