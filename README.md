# Cordaptor

Cordaptor is an open source project designed to address the needs of teams building decentralized applications
on [Corda](https://github.com/corda/corda) or integrating CorDapps with other systems. Corda is a great engine
for decentralized applications, but in order to communicate with a Corda node, teams have to develop bespoke
integrations using its Java client libraries. This comes with a steep learning curve, and adds complexity for
for teams working outside Java ecosystem, e.g. Node.js, .Net, or Python.

Cordaptor solves this problem by automatically creating REST API for any CorDapp running on a Corda node. There are
many tools that understand REST APIs in every technology stack, and teams can pick and choose what works for them.

## Features

* Zero-configuration deployment option as an embedded Corda service -- great for development and integration testing
* Generation of [OpenAPI 3](https://github.com/OAI/OpenAPI-Specification) JSON spec for the API endpoint based 
  on actual flows and contract state classes of available CorDapps
* Embedded [Swagger UI](https://swagger.io/tools/swagger-ui/) for exploring the API in your browser
* Docker-friendly standalone deployment option configurable via environment variables and compatible with
  Kubernetes secrets management
* Synchronous or asynchronous execution of Corda flows via the API
* Comprehensive API for node vault queries
* Flexible API security based on [PAC4J](https://www.pac4j.org/) allowing such authorization models as API keys,
  OpenID Connect, SAML 2, or just about anything else -- great for integrating managed services like AWS Cognito
* Full support for SSL out of the box
* Extensible architecture allowing bespoke features to be added as simply as dropping a JAR file into a directory

## Project status

At the moment Cordaptor is a technology preview made available to the wider community to gather feedback and identify
areas for improvement. Cordaptor's codebase is derived from a proprietary technology developed and battle-tested by
[Bond180](http://www.bond180.com) as part of its digital assets issuance and administration platform
[IAN](http://www.bond180.com). At the moment Cordaptor is not considered production-ready yet.

## Getting started

Cordaptor is designed from ground up to be unobstructive, so there is no code or configuration required!
Simply download the embedded bundle JAR file from releases and drop it into `cordapps` 
directory of your Corda node, restart it, and fire up your browser to access the Swagger UI.

Read more in [Getting started]() guide about other ways to get immediately productive with Cordaptor.

## Useful links

* [Cordaptor documentation]()
* [Developers' blog]()

## Contributing

Cordaptor is an open-source project and contributions are welcome!

## License

[GNU Affero General Public License version 3 or later](./LICENSE)

SPDX:AGPL-3.0-or-later

Copyright (C) 2020 Bond180 Limited

**Important notice**: for the avoidance of doubt in the interpretation of the license terms,
the copyright holders deem the following uses of Cordaptor to be 'aggregate' as opposed to 'modified versions':
1. Deploying embedded Cordaptor bundle JAR file into a Corda node, regardless of whether it is a file
distributed as a binary or built from the source code, as long as the source code remains unmodified.
2. Creating extensions for Cordaptor using it's published microkernel's and modules' API, where the
extensions' code is assembled into separate JAR files and made available for Cordaptor microkernel
to dynamically discover at runtime.
3. Including Cordaptor as a component of a broader application architecture where other components interact with it
using network protocols.
