# Extending Cordaptor

Cordaptor design is inspired by the [microkernel architecture](https://en.wikipedia.org/wiki/Microkernel), whereby
minimal set of functionality is implemented in the core, and the actual functionality is provided by
modules loaded dynamically at runtime.

The rationale is two-fold. Firstly, Cordaptor must support
[different deployment models](./architecture.md) from the same codebase (embedded and standalone),
which requires internal decoupling and, in case of the embedded CorDapp bundle, use of version-specific internal APIs,
which cannot be easily accommodated in a monolithic codebase. Secondly, Cordaptor functionality
may conceivably be augmented in a number of extension points (see [use cases](#use-cases) below),
and microkernel architecture makes such extensibility a first-class citizen, as opposed to
coming up with an ad hoc mechanism.

## Use cases

The following is a non-exhaustive list of reasons for extending Cordaptor:
1. Cordaptor may be used as an application server where a minor application-specific
behaviour is required to be implemented close to the CorDapp without introducing another
runtime component (e.g. a standalone web service using Spring Boot). In particular,
developers can choose to add custom REST API endpoints.
2. Developers may provide bespoke implementations for authentication and authorization logic. For example,
integrating with enterprise-specific single sign-on infrastructure for service accounts.
3. Developers may provide bespoke implementations for secrets management depending on their chosen model.
4. Future versions of Corda may introduce new features or breaking API changes,
which would require different logic to be implemented to support the functionality of Cordaptor API operations.
Such implementations would need to be compiled against different versions of Corda libraries.
5. Developers may chose to use different transport protocols for Cordaptor API, e.g. GraphQL or gRPC.
6. Developers may need to override default JSON serialization behaviour for some types.

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

## Extension author's guide

### General overview

Cordaptor microkernel uses Java's
[ServiceLoader](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/ServiceLoader.html)
utility to discover Cordaptor modules available in JVM classpath at startup. In nutshell, the utility looks for
files called `tech.b180.cordaptor.kernel.ModuleProvider` inside `META-INF/services` directories of all
JAR files in the classpath. Contents of the file is a newline-separated list of fully-qualified class names
implementing `tech.b180.cordaptor.kernel.ModuleProvider` interface. By convention these classes are called
`KoinModule`, but it could be anything. The microkernel will use ServiceLoader to instantiate all the classes
named in all the files using their default no-argument constructor.

This method is consistent across standalone and embedded modes of deploying Cordaptor. However, the limitation of 
ServiceLoader is that it ignores duplicate entries in a JAR file, which means that in the embedded CorDapp bundle,
there is a single entry under `META-INF/services` referencing all `KoinModule` classes bundled into the CorDapp JAR.

`ModuleProvider` interface is contains everything microkernel requires to correctly instantiate and resolve
dependencies between components of the module and other modules. The following describes the way microkernel uses
properties and methods on the interface:
* `salience` returns a number that is used to order the invocations of `provideModule` methods for all
instantiated concrete implementations of `ModuleProvider` interface. This is important because Koin
allows definitions in a module to be overridden by another module, but only if they are loaded later in the
initialization process. The value of `salience` does not have any meaning, and there are no consequences of
using the same value in different modules.
* `configPath` is a configuration path expression pointing to a location in the application configuration
namespace where module-specific configuration keys are located
(see [configuration guide](./configuration.md) for details). The microkernel will skip loading any module, for which
a false value is given to the key `enabled` immediately under the section identified by the value of `configPath`.
If the module is enabled, the microkernel will pass an object representing all configuration keys under the section
identified by the value of `configPath` to `provideModule` method.
* `provideModule` method is called for all concrete classes implementing `ModuleProvider` interface that are found
through ServiceLoader. Module's own section of the application configuration namespace is passed as an argument.
The implementation of the method is expected to return a Koin module definition declared using Koin's
[Module DSL](https://doc.insert-koin.io/#/koin-core/definitions),
which is effectively a way to describe how to instantiate and wire-up classes contributed by the module.

It's recommended to refer to specific implementations of `ModuleProvider` interface of the Cordaptor modules
for examples.

The microkernel itself provides a number of Koin definitions that could be injected when it instantiates
classes contributed by the module:
* `tech.b180.cordaptor.kernel.LifecycleControl` allows changing global application lifecycle stage based
on the event occurring within the module.
* `tech.b180.cordaptor.kernel.Config` if injected via the interface, provides access to the root
application configuration namespace allowing reading other modules' configuration. This needs to be done
with care, as configuration keys may vary between module versions. It's better to map other modules'
configuration keys to the keys within your extension namespace using substitutions syntax (`${...}`),
because these could be changed without changing the extension's code.
Refer to the [configuration guide](./configuration.md) for details.
* `tech.b180.cordaptor.kernel.SecretsStore` provides access to secrets management functionality.
Default implementation uses application configuration namespace to store secret values, but other
modules may provide a different implementation that would override the definition, but the interface will
remain the same. Note that module's code is unlikely to use this interface directly and instead will
rely on the instances of `tech.b180.cordaptor.kernel.Secret` interface obtained via `Config`.

Note that Koin's built-in [support for properties](https://doc.insert-koin.io/#/koin-core/properties)
is not used in Cordaptor, as its functionality is quite limited.

In Koin definitions may
[bind to additional interfaces](https://doc.insert-koin.io/#/koin-core/definitions?id=additional-type-binding).
The microkernel defines some interfaces which allow modules to use various mechanisms built into the core:
* `tech.b180.cordaptor.kernel.LifecycleAware` allows the microkernel to notify components that
the application's global lifecycle stage is changing.

Other modules may provide other interfaces that the module's definition could bind to, in order to
extend the functionality offered by those modules. For example, **cordaptor-rest-endpoint** module defines
`tech.b180.cordaptor.rest.QueryEndpoint` interface representing an API operation available via HTTP GET request.
Modules could contribute definitions bound to this interface in order to add additional API operations.

The microkernel provides `tech.b180.cordaptor.kernel.CordaptorComponent` interface, which may
be implemented by components of the modules to simplify access to the functionality of the microkernel.

## Public vs private module APIs

Most classes and Koin definitions contributed by a module would be private to the module's implementation.
At this time the microkernel will allow injecting and binding to any class or interface contributed by any module.
This is not recommended, because module implementations may change between versions
without notice, and this may break compatibility of extensions. This may also breach the license for
some modules.

To explicitly allow extensions to use classes of a module, its authors may opt to use annotation types
provided by the microkernel:
* `tech.b180.cordaptor.kernel.ModuleAPI` indicates that a type, or a particular constructor, method, or property
is part of the public contract of the module. If the annotation is used on a type, then all its methods,
constructors and properties are also considered to be annotated. The annotation has a mandatory parameter `since`,
containing the first version of the module, in which the annotated feature was first introduced.
This specifically means that module's author commits to maintaining a bytecode compatibility of the
feature with the code that may rely on it.
* `tech.b180.cordaptor.kernel.DeprecatedModuleAPI` indicates that a type, or a particular constructor,
method, or property that is a part of the public contract of the module is no longer recommended to use,
as it is going to be removed in one of the upcoming versions. The annotation has a mandatory parameter `since`,
containing the version number of the module, in which the annotated feature became deprecated.

**Note that until 1.0 release, all built-in Cordaptor modules' public APIs are subject to change between
minor release versions.** Such changes will always be documented in release notes.

## Integrating with other modules

It is unlikely a useful extension will only be dependent on the microkernel. Most of the functionality
of Cordaptor is provided by its modules working together, and, in most cases,
the extensions will use classes and interfaces contributed by the Cordaptor modules.

The following sections provide a brief overview of Cordaptor modules and give pointers to relevant
classes and interfaces that extensions might want to use.
We recommend familiarising yourself with the types constituting the public API of Cordaptor modules
by following its code. We endeavour to maintain high quality code comments, not only about the types
and their features, but also the context where they might be sensibly used.

### cordaptor-corda-service

This module is responsible for instantiating Cordaptor microkernel as a Corda service within
the Corda node JVM and allowing other modules to access the functionality of the node,
e.g. querying the vault or invoking flows. This module is included into the embedded CorDapp bundle.

Two key interfaces this module contributes implementations for are `tech.b180.cordaptor.corda.CordaNodeCatalog` and
`tech.b180.cordaptor.corda.CordaNodeState`, which allow interrogating metadata about CorDapps and their
features available in the Corda node and interacting with the node's vault and flows respectively.
There are also implementations of these interfaces that use Corda APIs available inside the node.

Note that the above interfaces themselves are part of **cordaptor-corda-common** shared library. However, the library
itself only contains the definitions and various helper functions, and is not a module in its own right.
Instead, **cordaptor-corda-service** contributes components that use classes from the library.
The rationale is that the library contains common logic shared between this module and the next one,
but this logic is only meaningful in the context of one of the two modules.

This module contributes public interface `tech.b180.cordaptor.cordapp.NodeServicesLocator`,
which allows other modules to use internal Corda APIs. However, even through this interface
is a part of the module API, other modules should exercise caution because it will only be available when Cordaptor
is deployed as an embedded Corda service.

### cordaptor-corda-rpc-client

This module is responsible for instantiating Cordaptor microkernel in a standalone JVM, and
maintaining connection to a Corda node via Corda RPC over the network. It also allows other modules
to access the functionality of the node, e.g. querying the vault or invoking flows.
This module is used as part of the standalone deployment.

As with the previous one, this module contributes implementations of
interfaces `tech.b180.cordaptor.corda.CordaNodeCatalog` and
`tech.b180.cordaptor.corda.CordaNodeState`, which allow interrogating metadata about CorDapps and their
features available in the Corda node and interacting with the node's vault and flows respectively.
Unlike **cordaptor-corda-service**, this module implements these interfaces using Corda RPC calls.
Other modules can safely rely on the methods of the interfaces to work regardless of whether
Cordaptor is embedded or standalone.

Note that the above interfaces themselves are part of **cordaptor-corda-common** shared library. However, the library
itself only contains the definitions and various helper functions, and is not a module in its own right.
Instead, **cordaptor-corda-rpc-client** contributes components that use classes from the library.
The rationale is that the library contains common logic shared between this module and the previous one,
but this logic is only meaningful in the context of one of the two modules.

This module contributes an implementation of interface `tech.b180.cordaptor.rpc.CordaRPCOpsLocator`,
which allows other modules to use Corda RPC operations directly. However, even through this interface
is a part of the module API, other modules should exercise caution because it will only be available when Cordaptor
is deployed as a standalone process.

### cordaptor-rest-endpoint

This module exposes an HTTP-based REST API endpoint using embedded [Undertow](https://undertow.io/) web server.
It also provides mechanisms for securing the API endpoint. In future this module may also implement
a WebSocket API allowing bidirectional real-time interaction with Corda node.

This module is included into both embedded and standalone modes, and the way it works is identical in either
deployments due to the fact it uses deployment-agnostic interfaces `tech.b180.cordaptor.corda.CordaNodeCatalog` and
`tech.b180.cordaptor.corda.CordaNodeState` (see above). This is an example of how extensions can
be implemented to work regardless of the actual deployment architecture.

The module offers a number of useful extension points:
1. It provides a comprehensive JSON serialization/deserialization framework using Corda introspection
logic under the hood. Extensions can implement custom serializers for certain types by
binding components to secondary interface `tech.b180.cordaptor.rest.CustomSerializer`. Other relevant public API
types are `tech.b180.cordaptor.rest.StandaloneTypeSerializer` and
`tech.b180.cordaptor.rest.CustomSerializerFactory`. Refer to code comments for details of how to use them.
2. It uses PAC4j security framework to protect API endpoints. Extensions can contribute bespoke
configurations of the authentication logic using PAC4J API. Extensions can contribute
[named](https://doc.insert-koin.io/#/koin-core/definitions?id=definition-naming-amp-default-bindings)
Koin components implementing `tech.b180.cordaptor.rest.SecurityHandlerFactory` interface.
Different implementations of the interface may exist at the same time, and the module
will use configuration key `openAPI.security.handler` when requesting an implementation of the interface by name from
the microkernel. The rationale for this is that for some deployment scenarios there may be a need
to activate different authentication mechanism without changing the deployment package. 
3. It provides a framework for creating REST API endpoints. Extensions can implement additional API endpoints by
[binding components to additional interfaces](https://doc.insert-koin.io/#/koin-core/definitions?id=additional-type-binding)
`tech.b180.cordaptor.rest.QueryEndpoint` (HTTP GET) or `tech.b180.cordaptor.rest.OperationEndpoint` (other HTTP methods).
Extensions can also contribute implementations of `tech.b180.cordaptor.rest.EndpointProvider`,
which is used as a factory class.

### cordaptor-local-cache

This module provides in-memory cache maintaining flow results after they complete. Work is underway
to add further caching features such as ability to maintain a replica of the vault to provide faster response
time for queries and protect the node from excessive load.

This module overrides implementations of interfaces `tech.b180.cordaptor.corda.CordaNodeCatalog` and
`tech.b180.cordaptor.corda.CordaNodeState` provided by the modules specific to the deployment architecture,
but works in a deployment-agnostic way. It also contributes an implementation of
`tech.b180.cordaptor.corda.CordaFlowSnapshotsCache` interface.

## Building and deploying extensions

Extensions must be compiled as a Java library into a JAR file. When configuring the build we recommend
using Maven to resolve dependencies on other modules, as well as the microkernel.

For example, if using Gradle to build extension contributing additional REST API endpoints,
the `build.gradle` file would contain the following dependencies:

```gradle
dependencies {
  implementation "tech.b180.cordaptor:cordaptor-kernel:0.1.0"
  implementation "tech.b180.cordaptor:cordaptor-rest-endpoint:0.1.0"
}
```

Extensions JAR files must be 'thin', i.e. only include compiled classes that belong to the extension.
If the extension has dependencies on external libraries which are not included in Cordaptor, they should be made
available in the classpath using the same mechanism as for the extension itself (see below).
In this case watch out for conflicts arising when multiple versions of the same library finds its way
into the classpath. At the moment there is no mechanism for separating the classpath into independent sections.

Every extension must provide a class implementing `tech.b180.cordaptor.kernel.ModuleProvider` interface,
and place its fully-qualified name in an entry under `META-INF/services` (see above). This will allow
the microkernel to initialize extension's Koin module definition.

Finally, extension JAR must should include a reference configuration file, even if the module itself
does not have any configurable properties. This is because the microkernel will use `enabled`
configuration key within the module's namespace to determine if it needs to be loaded (see above).

The file is always called `module-reference.conf` and placed into the JAR file
as a root entry, i.e. not into a subdirectory. The below is an example of a minimal `module-reference.conf` file:

```hocon
moduleNamespace { // placeholder
  enabled = true
}
```

Note that in the actual extension configuration `moduleNamespace` above needs to be the same as
the value returned by property `confPath` of the class implementing
`tech.b180.cordaptor.kernel.ModuleProvider` interface.

Deployment of an extension varies depending on whether Cordaptor is running standalone or embedded.
* In the standalone mode, Cordaptor will add to classpath all files found under `extensions` subdirectory
of the distribution. When running Cordaptor in a Docker container, extensions JAR files may be
mounted from the host to avoid building a dedicated image.
* In the embedded mode, Cordaptor will use Corda node's classloading mechanism. Extensions could be
built as CorDapps, which are normal JAR files with additional manifest entries. Another alternative
is to set [jarDirs](https://docs.corda.net/docs/corda-os/4.6/corda-configuration-fields.html#jardirs)
node configuration property for the node, and then add Cordaptor extensions JARs there.

As of Corda 4, node classpath forms a flat namespace, which makes all classes visible anywhere (leaving DJVM aside).
It is possible to contribute Cordaptor extensions by bundling them into your 'normal' CorDapp JAR.
This will work in both standalone and embedded modes, because Cordaptor requires CorDapp JARs to be available.
However, we do not recommend this, in the light of potential changes to classloading in Corda 5.
