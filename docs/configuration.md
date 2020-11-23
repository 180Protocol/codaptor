# Configuring Cordaptor

Please see [configuring CorDapps](#configuring-cordapps) section below if you are looking for a way to customize
Cordaptor's default behaviour for a specific CorDapp.

Cordaptor uses excellent [lightbend/config](https://github.com/lightbend/config) library configuring
just about anything running in JVM, which comes with a lot of features out of the box. Corda itself also
uses the same library, but unfortunately does not make underlying structures available
for CorDapps. This necessitated taking different approaches to configure Cordaptor
working as an embedded CorDapp bundle, as opposed to the standalone deployment.

The following section provides general information about Cordaptor configuration, and subsequent sections
elaborate on points specific to different deployment models.

## General information

Cordaptor itself consists of a microkernel responsible for discovering and loading Cordaptor modules,
and the modules themselves, which are responsible for implementing the features of Cordaptor.
Configuration is managed centrally by the microkernel, which provides a wrapper for `lightbend/config` classes.
The wrapper is necessary to make Cordaptor configuration API is consistent across deployment models (see below).

Read about Cordaptor microkernel and its configuration management features in the [extensions guide](./extensions.md).

Configuration files follow the rules of
[Human-optimized config object notation (HOCON)](https://github.com/lightbend/config/blob/master/HOCON.md),
which is a rich JSON-inspired language for data literals supporting comments, scalars, lists, nested objects,
substitutions, and includes. The following is a valid snippet of HOCON showcasing various features:

```hocon
section { // object with nested keys
  string = ABC // no quotes required
  list = [ A, B, C ] // list literal
  list += D // adding item to the list

  env = ${ENVIRONMENT_VARIABLE} // mandatory substitution from the environment
  value = ${?OPTIONAL_VARIABLE} // optional substitution from the environment

  subsection {
    boolean = false // boolean literals are supported
    timeout = 10m // suffixes for duration values
    maxSize = 10MiB // various byte size suffixes are supported, note that MiB is 2^20, and MB is 10^6
  }
}

include file("resource") // inclusion of another file, can also be url(...) or classpath(...)

section.key = 321 // another way to change value in the nested object
anotherKey = ${section.list} // substitution from another place in the config
```

Cordaptor microkernel resolves modules configuration in two steps. At first, it loads a _reference
configuration_ for each module, and constructs a reference configuration namespace. All Cordaptor
modules include files called `module-reference.conf`, which are dynamically discovered in the classpath.
Then it locates an _application configuration_ file and uses it to override some keys
in the reference configuration namespace. Application configuration file is located in a deployment-specific
way, which are covered in subsequent sections.

By convention each Cordaptor module owns a top-level object in the overall configuration namespace. Reference
configuration files are well-documented and are the best, and most up to date source of information
on how to configure a particular module:
* **corda-service**: namespace `cordaService`, reference [module-reference.conf](../corda-service/src/main/resources/module-reference.conf)
* **corda-rpc-client**: namespace `rpcClient`, reference [module-reference.conf](../corda-rpc-client/src/main/resources/module-reference.conf)
* **local-cache**: namespace `localCache`, reference [module-reference.conf](../local-cache/src/main/resources/module-reference.conf)
* **rest-endpoint**: namespace `openAPI`, reference [module-reference.conf](../rest-endpoint/src/main/resources/module-reference.conf)

Any Cordaptor module could be disabled by setting `enabled` key in its top-level object to `false`, e.g.
to disable **local-cache** module set `localCache.enabled` key to `false`.

A common idiom occuring in a number of reference configurations is to use a literal value followed
by an optional environment variable substitution. For example, from **rest-endpoint** module:

```hocon
openAPI {
  webServer {
    listenAddress = "127.0.0.1:8500"
    listenAddress = ${?CORDAPTOR_API_LISTEN_ADDRESS}
  }
}
```

This means that Cordaptor will by default listen on the loopback interface and port 8500,
but if another value is provided to the environment variable `CORDAPTOR_API_LISTEN_ADDRESS`,
it will override the default. In such way, some common configuration values could be easily
overridden (e.g. when running in a container) without the need to provide a different config file.

## Configuring standalone Cordaptor

Standalone Cordaptor configuration is straightforward. Application configuration file
overriding the reference configuration of the modules is located at `<cordaptor home>/conf/cordaptor.conf`.

The file included with the distribution is effectively a commented-out reference configuration
produced by concatenating `module-reference.conf` files from all modules. It is included for your
reference, and there is no point uncommenting any section unless you want to change any value.
More targeted way to change a particular configuration key is to use _path expressions_, for example
the following configuration entries are equivalent:

```hocon
openAPI {
  webServer {
    externalAddress = "hostname:80"
  }
}
```

has the same effect as

```hocon
openAPI.webServer.externalAddress = "hostname:80"
```

## Configuring embedded CorDapp bundle

When Cordaptor modules are bundled into an embedded CorDapp JAR, their `module-reference.conf` files are
concatenated, and the bundle itself also overrides certain keys. Most users should not be concerned
about this, and in practice reference configuration namespace for all Cordaptor modules would be
identical to the one in a standalone deployment.

Application configuration for embedded CorDapp bundle is provided using Corda's own facility for
[configuring CorDapps](https://docs.corda.net/docs/corda-os/4.6/cordapp-build-systems.html#cordapp-configuration-files).
In nutshell, each CorDapp loads its own configuration from `<node_dir>/cordapps/config` directory, and
the name of the file must match the name of the CorDapp JAR.

Cordaptor configuration will need to be placed into `<node_dir>/cordapps/config/bundle-rest-embedded-0.1.0.conf`.

Corda supports most features of `lightbend/config` for CorDapp configuration, but not everything.
Cordaptor abstracts away the differences for modules by introducing its own configuration API,
which attempts to compensate for the gaps, but it is not always possible and there are some notable differences:
* CorDapp configuration files do not have built-in support for byte size and duration suffixes.
Cordaptor provides its own implementation based on `lightbend/config`, but it may behave
differently in some cases. It is advisable to test configuration changes before applying them in production.
* CorDapp configuration files do not support lists of values for the keys. Cordaptor provides
limited support for lists of strings, by using comma as a separator for different values.

Corda provides options for configuring CorDapps in
[integration tests using TestCordapp](https://api.corda.net/api/corda-os/4.5/html/api/kotlin/corda/net.corda.testing.node/-test-cordapp/with-config.html), as well as
[Cordformation plugin](https://docs.corda.net/docs/corda-os/4.6/cordapp-build-systems.html#using-cordapp-configuration-with-the-deploynodes-task)
(aka `deployNodes` task). Cordaptor can be configured using either of these methods. Refer
to the compatibility test suites for the [embedde bundle](../reference-cordapp/src/embeddedBundleTest)
and reference cordapp's [build.gradle file](../reference-cordapp/build.gradle) for examples.

## Secrets management

Cordaptor comes with an extensible framework for integrating with various ways secrets
are managed across environments. Microkernel's API provides `Secret` and `SecretsSource` interfaces,
which could be implemented differently via extensions. All built-in modules use secrets API
for all sensitive information like usernames and passwords, and contributed alternative implementation
will be picked up automatically.

By default, Cordaptor uses the implementation that relies on the configuration for secrets.
Thanks to the features of `lightbend/config` itself, this gives two highly practical options
that are commonly used for container deployments and, specifically, supported by
[Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/):
1. Configuration keys representing values for secrets could have optional substitutions from
the environment variables (see example above), which could be passed in as arguments when starting
the container. All built-in Cordaptor modules follow this convention for all sensitive information,
see comments in reference configuration files for details.
2. Configuration keys representing values for secrets could be extracted into a separate configuration
file that is mounted by the container engine and referenced from the application configuration using
`include file(...)` directive. See example above.

Neither of the above methods is bullet-proof against a compromised environment where the attacker
has privileged access. In any case you should do your own security risk assessment against practical
attack vectors.

Note that the built-in secrets manager does not support secrets rotation at the moment. Cordaptor
kernel reads the configuration only once during the bootstrap.

Another aspect to be aware is that, due to the use of `lightbend/config`,
the built-in secrets manager reads secrets as Java strings,
which may be kept in JVM for a long time. This is a moot point in some cases, as many APIs,
Corda RPC in particular, also require usernames and password to be passed in as Java strings.
However, Java SSL API uses `char[]` for keystore passwords, and if you want to use SSL with Cordaptor
and concerned about the use of Java strings in this case, we recommend using a different implementation
for the secrets manager.

We are planning to develop adapters for most commonly used tools like
[Amazon Secrets Manager](https://aws.amazon.com/secrets-manager/) and
[HashiCorp Vault](https://www.hashicorp.com/products/vault),
but we welcome contribution of modules from the community.

## Hardening for production

It is recommended to switch off OpenAPI JSON specification and Swagger UI when running Cordaptor in production,
unless your clients are dependent on either of them.

## Configuring CorDapps

One of the key design intentions for Cordaptor is to work out of the box for any reasonable CorDapp.
To that end it assumes as little as possible about the nature of the CorDapp, and
relies on introspection and sensible defaults to configure its behaviour.
Unfortunately, this is not always possible. Corda programming model is designed without an open API in mind,
and sometimes default assumptions do not make sense.

To allow CorDapps to change how Cordaptor configures the API for their classes, we provide
a dedicated configuration mechanism. Developers can add a file called `cordaptor.conf` into
the `META-INF` folder of their CorDapp JARs. This file is read during the introspection
of the available CorDapps before API endpoints are instantiated.

Bundled `cordaptor.conf` files use
[Human-optimized config object notation (HOCON)](https://github.com/lightbend/config/blob/master/HOCON.md),
alongside core Cordaptor configuration, but they are resolved in isolation from the latter,
so other configuration keys cannot be referenced from it.

The following keys are supported by Cordaptor:
* `urlPath` - prefix used by Cordaptor for API endpoints corresponding to flows and contract state
queries for the classes bundled into this CorDapp JAR file. This is handy if CorDapp short name
property is not URL-friendly, and cannot be easily changed.