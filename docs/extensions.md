# Extending Cordaptor

This is a skeleton documentation that is being worked on.

Cordaptor design is inspired by [microkernel architecture](https://en.wikipedia.org/wiki/Microkernel), whereby
minimal set of functionality is implemented in the core, and all the features are delegated to separate
modules loaded dynamically at runtime.

The rationale is two-fold. Firstly, Cordaptor must support
[different deployment models](./architecture.md) from the same codebase (embedded and standalone),
which requires internal decoupling and, in case of the embedded CorDapp bundle, use of version-specific APIs,
which cannot be easily accommodated in a monolithic codebase. Secondly, Cordaptor functionality
may conceivably be augmented in a number of extension points, and microkernel architecture makes
such extensibility a first-class citizen, as opposed to coming up with an ad hoc way.

## Use cases

The following is a non-exhaustive list of reasons for extending Cordaptor:
1. Cordaptor may be used as an application server where a minor application-specific
behaviour is required to be implemented close to the CorDapp without introducing another
runtime component (e.g. a standalone web service using Spring Boot). In particular,
user can easily implement custom REST API endpoints.
2. Users may provide bespoke implementations for authentication and authorization logic. For example,
integrating with enterprise-specific single sign on infrastructure for service accounts.
3. Users may provide bespoke implementations for secrets management depending on their chosen model.
4. Future versions of Corda may introduce new features or breaking API changes,
which would require different logic to be implemented to support the functionality of Cordaptor API operations.
Such implementations would need to be compiled against different versions of Corda libraries.
5. Users may chose to use different transport protocols for Cordaptor API, e.g. GraphQL or gRPC.
6. Users may need to override default JSON serialization behaviour for some types.

## Microkernel

Cordaptor features a rich microkernel providing a number of useful features.
* Fully-featured yet lightweight dependency injection powered by [Koin framework](https://insert-koin.io/)
* Flexible yet consistent API reading configurations powered by [lightbend/config](https://github.com/lightbend/config)
supporting both embedded and standalone deployments
* Generic and extensible secrets management API with a sensible default implementation
* Dynamic module discovery and initialization from the classpath based on Java
[ServiceLoader API](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)
* Lifecycle events notifications and management
* Consistent logging API for the modules powered by [SLF4J](http://www.slf4j.org/) and using
[log4j 2.x](https://logging.apache.org/log4j/2.x/index.html), which is compatible
with Corda own logging implementation.

## Cordaptor modules

* **kernel** - Microkernel responsible for locating other modules in the classpath, managing component lifecycles,
providing configuration, and resolving inter-component dependencies.
* **corda-service** - Responsible for instantiating Cordaptor as a Corda service within Corda node JVM and allowing
other modules to access the functionality of the node's internals, e.g. querying the vault or invoking flows.
This module is included into the embedded CorDapp bundle.
* **corda-rpc-client** - Responsible for connecting with a Corda node via Corda RPC and managing this connection,
whilst also providing other modules with access to the functionality of the node. This module is used
as part of the standalone distribution.
* **rest-endpoint** - Exposes an HTTP-based REST API endpoint based on embedded Jetty web server. Provides mechanisms
for securing the API endpoint. Implements WebSocket API endpoint for real-time interaction with Corda node.
* **local-cache** - Uses local in-memory cache maintaining flow results after they have completed.
Also able to maintain a replica of the vault to provide faster response time for queries and protects
the node from excessive load (work in progress)
