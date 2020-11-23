# Using Cordaptor

This is a guide on various aspects of Cordaptor functionality. It presumes you already have
Cordaptor up and running. If not, please read how to [get started with Cordaptor](./getting-started.md).

## Using Cordaptor API

Cordaptor exposes a web service offering a [REST API](https://en.wikipedia.org/wiki/Representational_state_transfer).
To put simply, this is a collection of URLs to which HTTP requests with a particular command (e.g. GET or POST)
optionally accompanied by additional parameters, as well as a request payload could be sent.

REST API exposed by Cordaptor is created dynamically during the startup and based on the classes of the
CorDapps available to introspection. The following sections explain the kinds of functionality generated API
would normally offer.

### Swagger UI and OpenAPI specification

The best way to explore automatically generated API is to use built-in
[Swagger UI](https://swagger.io/tools/swagger-ui/), which is by default available at http://localhost:8500/swagger.
Swagger UI shows all resources available for interaction, operations supported by them, and allows to
send requests and see responses right from the browser.

When Swagger UI page loads, it attempts to fetch OpenAPI JSON specification file from `/api.json` URL.
If you want to use another tool for exploring the API, for example [Postman](https://www.postman.com/),
you can point it directly at http://localhost:8500/api.json to generate a collection.

Teams working with strongly-typed languages may prefer to generate client classes for APIs they use.
Cordaptor supports this use case by attempting to extract as much type information as possible from
the underlying CorDapp classes and make it available in the form of JSON Schema documents embedded
into the OpenAPI specification. Code generation tools can create rich set of types from JSON Schema.
For example, to create typesafe client classes for TypeScript we recommend using
[Autorest](https://github.com/Azure/AutoRest). Other tech stacks have similar tools, and we would
love to hear what worked for you.

Note that Swagger UI and OpenAPI JSON specification endpoints are enabled by default, but could be disabled
for added security. See [configuration guide](./configuration.md) for details.

### Node information

The following operations are always available in Cordaptor API in order to query basic
information about the underlying Corda node itself.

* `/node/info` accepts an HTTP GET request without parameters, and returns information about the
node that is encoded stored in nodeInfo file on the node, as well as on the Corda network map service.

Practical use of this query is to configure client application to adopt a particular legal identity for itself,
especially when then same client code is used for a number of different Corda nodes.

The following is a typical response returned by the operation:

```json
{
  "addresses": [
    {
      "host": "bank-node",
      "port": 10200
    }
  ],
  "legalIdentitiesAndCerts": [
    {
      "party": {
        "name": "O=Bank, L=London, C=GB"
      }
    }
  ],
  "platformVersion": 7,
  "serial": 1605030798862
}
```

* `/node/version` accepts an HTTP GET request without parameters and returns
compatibility information for the software running the Corda node.

The following is a typical response returned by the operation.

```json
{
  "platformVersion": 7,
  "releaseVersion": "4.5",
  "revision": "461cf0709ca6788ec2247d12be6b2656fe718ea2",
  "vendor": "Corda Open Source"
}
```

### Flow initiation endpoint

Each concrete flow class in CorDapps available for Cordaptor's introspection will have an
initiation endpoint to be created. Flow classes extend `net.corda.core.flows.FlowLogic<T>` or
any of its subclasses.

The endpoint resource URLs are constructed using the following template:  
`/node/<cordapp short name>/<flow class simple name>`,  
where _cordapp short name_ is the value of **Cordapp-Workflow-Name**
manifest attribute from the CorDapp JAR file (unless [overridden](./configuration.md#configuring-cordapps)),  
and _flow class simple name_ is the name of the flow class without its package.

E.g. for `tech.b180.ref_cordapp.SimpleFlow` flow class defined in **reference-cordapp** module,
the flow initiation endpoint URL will be `/node/reference/SimpleFlow`.

Flow initiation endpoint accepts an HTTP POST request with a payload containing JSON object representing
constructor parameters for the flow class, as well as flow initiation options. Constructor parameters will
be inferred dynamically from the actual flow class, and documented in the corresponding JSON Schema object
of the generated OpenAPI specification.

Note that Cordaptor attempts to preserve as much type information as possible. In particular,
it will honor nullability of arguments' types, and will make nullable arguments optional. Further,
if the flow class constructor takes parameters of generic types, JSON schema will be generated
with full awareness of the actual types used. For example, if a particular flow class expects
an instance of `java.util.List<String>`, then JSON Schema will be specific that a JSON array of strings
must be provided. This will allow automatically generated client code libraries to use appropriate types.

Flow initiation options supported by Cordaptor at the moment are:
* `trackProgress` (boolean) whether Corda flow progress tracker needs to be subscribed to, and
if flow progress updates need to be made available in the flow snapshots (see below).

Flow initiation endpoint will always return a JSON payload representing a _flow snapshot_, which contains
either information about flow's latest progress step update (which may be empty, if not provided by the flow,
or not requested by the client), or flow result, which may be either a JSON representation of the object
returned by the `call` method of the flow class, or an exception thrown by the flow class itself or Corda
during the execution of the flow.

Flow snapshot will also provide a run id for the flow instance, corresponding to Corda's `StateMachineRunId`
uniquely identifying an instance of the flow class within the node's state machine. This is a UUID,
which could be used to retrieve the latest snapshot of the flow.

The following is a typical response for the endpoint initiating the flow without a timeout:
```json
{
  "flowClass": "tech.b180.ref_cordapp.SimpleFlow",
  "flowRunId": "cda23683-7040-41b2-9cb4-9732edd3d30c",
  "startedAt": "2020-11-17T16:38:15.351Z"
}
```

The endpoint may take an optional parameter `wait`, containing a non-negative integer representing
number of seconds API endpoint will attempt to wait for flow to complete before returning a response.
If omitted, `wait` is assumed to be 0, which means that the endpoint will not wait at all
and return a snapshot straight away. For any above-zero value, Cordaptor will wait for that number
of seconds maximum. If flow completes before that time, the response will be returned straight away,
otherwise once the timeout is reached, the response will contain the latest flow snapshot.

If flow completes before the timeout, the response will have `200 OK` HTTP status code. If the
wait was not requested or timed out, then the response will have `202 Accepted` HTTP status code.
In such case `Location` header on the response will also be set to point at the corresponding
flow snapshots endpoint (see below). This allows client code to simply use the returned URL and
not bother with URL templates.

The following is a typical response for the endpoint when the flow returned a result within the timeout:
```json5
{
  "flowClass": "tech.b180.ref_cordapp.SimpleFlow",
  "flowRunId": "0ecf70fc-852d-46da-b85b-4f563e749988",
  "result": {
    "timestamp": "2020-11-17T16:46:22.236Z",
    "value": { /* ... */ }
  },
  "startedAt": "2020-11-17T16:46:22.187Z"
}
```

Note that waiting for the flow to complete is asynchronous and
does not consume a worker thread in the Undertow's thread pool, which means there are no
practical limits on how many flows could be initiated through the API, as long as the underlying
Corda node can cope with them.

Maximum value for the `wait` parameter is determined by `openAPI.flowInitiation.maxTimeout`
configuration key, which is set to 10 minutes by default.

Note that when using a level 7 proxy like an application load balancer or an API gateway, maximum timeout
should be less than maximum request timeout on the proxy itself, because after that time client will
receive `504 Gateway Timeout` HTTP error anyway. For example, [Amazon API Gateway has 30 seconds maximum response
timeout](https://docs.aws.amazon.com/apigateway/latest/developerguide/limits.html), which cannot be changed,
so asking Cordaptor to wait for the flow to complete for longer will unnecessarily consume server resources.
Further, it may make it harder to clients to determine the outcome of the flow execution, because they
did not receive flow run id (see below).

Note that because Cordaptor knows the flow result type when creating this endpoint, it can generate
fully typed JSON Schema definitions, which means any client code generated from the schema will be
typesafe.

### Flow snapshots endpoint

Flow snapshot returned by flow initiation API endpoint contains information about flow's latest
progress step update or its outcome (see above). The snapshot also contains flow run id, which is a UUID representing
a unique identifier of the flow instance within Corda node's state machine.

Cordaptor API provides flow snapshots endpoint for clients willing
to obtain the latest flow snapshot using flow run id.

The endpoint resource URLs are constructed using the following template:  
`/node/<cordapp short name>/<flow class simple name>/snapshot/<flow run id>`, where  
_cordapp short name_ is the value of **Cordapp-Workflow-Name** manifest attribute from the CorDapp JAR file
(unless [overridden](./configuration.md#configuring-cordapps)),  
_flow class simple name_ is the name of the flow class without its package, and  
_flow run id_ is the UUID returned by the flow initiation endpoint

E.g. if an instance of flow class `tech.b180.ref_cordapp.SimpleFlow` defined in **reference-cordapp** module
was initiated by the flow initiation endpoint with flow run id `cda23683-7040-41b2-9cb4-9732edd3d30c`,
then flow snapshots endpoint URL for this flow class will be
`/node/reference/SimpleFlow/snapshot/cda23683-7040-41b2-9cb4-9732edd3d30c`.

The endpoint would only accept an HTTP GET request. Aside from the flow run id encoded in the URL, the operation
does not accept any further parameters.
The operation returns a flow snapshot straight away if a given flow run id corresponds to a known
flow instance with `200 OK` status code, or fail with `404 Not Found` HTTP status code if not.

Note that up until version 4.6 Corda itself did not provide means to obtain information about completed flows.
Instead, information about completed flows is cached in the Cordaptor itself using **local-cache** module.
This behaviour could be fine-tuned per flow class. See [configuration guide](./configuration.md) for details.
Corda 4.6 introduced
[new API for starting flows with clientId parameter](https://docs.corda.net/docs/corda-os/4.6/flow-start-with-client-id.html),
which may be used to achieve the same outcome without the use of local cache. Currently, Cordaptor does not
support this API, but it is the intention to make the operation work regardless of Corda version used.

If **local-cache** module is disabled, flow snapshots endpoint will not be available.
If snapshots caching is disabled for a particular flow class, then flow snapshots will
not be available after flow completes. Note that currently there is no way for the client to distinguish
situations when a given flow run id was never unknown, or when the flow snapshot was evicted from the cache.
This is by design, as flow snapshots cache is only local to the JVM,
which would be wiped clean should a restart happen, making the distinction unreliable.

### Transaction query endpoint

Cordaptor creates a generic transaction query endpoint at `/node/tx/<txid>` URL,
where _txid_ is a 40 character-long textual representation of Corda's `net.corda.core.crypto.SecureHash` object,
which is a unique identifier of a transaction in the vault.

For example, the following is a valid URL:  
`/node/tx/FF0D5832026786635B5EC0E3C8CBD5C7AFF275D7FE19DEAD6D50F5DB9DBB380F`

The endpoint accepts an HTTP GET request, and takes no further parameters.
If a transaction identifier is known to the Corda node,
the operation will return `200 OK` HTTP status code, and a JSON object representing a serialized
instance of `net.corda.core.transactions.SignedTransaction` class. If the transaction identifier is not
known to the Corda node, the operation will return `404 Not Found` HTTP status code.

Note that the operation result will be defined in JSON Schema using generic contract states,
without reference to specific JSON Schema types representing contract states available in the CorDapp.
This is because it is impossible to know by static code inspection what contract states could
be included into a Corda transaction. Actual JSON object returned by the operation at runtime will
always correspond to the actual types of contract states that are part of the transaction.

### Contract state query endpoint

Each concrete contract state class in CorDapps available for Cordaptor's introspection will have a
state query endpoint created. Contract state classes extend `net.corda.core.contracts.ContractState` or
any of its subclasses.

State query endpoint
Node that this operation will return contract state information even if it is consumed in subsequent transactions.
The design intent is use this operation to resolve 'permanent' URLs for contract states.

The endpoint resource URLs are constructed using the following template:  
`/node/<cordapp short name>/<contract state class simple name>/<txid>(<index>)`, where  
_cordapp short name_ is the value of **Cordapp-Workflow-Name** manifest attribute from the CorDapp JAR file
(unless [overridden](./configuration.md#configuring-cordapps)),  
_contract state class simple name_ is the name of the contract state class without its package, and  
_txid(index)_ is a 40 character-long textual representation of Corda's `net.corda.core.crypto.SecureHash` object,
which is a unique identifier of a transaction in the vault, followed by an index of the output state
in the transaction.

E.g. for `tech.b180.ref_cordapp.SimpleLinearState` contract state class defined in **reference-cordapp** module,
a valid contract state query endpoint URL would be
`/node/reference/SimpleLinearState/FF0D5832026786635B5EC0E3C8CBD5C7AFF275D7FE19DEAD6D50F5DB9DBB380F(0)`.

The endpoint accepts an HTTP GET request and does not take any additional parameters. The operation
will return `200 OK` HTTP status code if the contract state is found, and a JSON object representing a serialized
instance of the contract state class. If the transaction identifier is not
known to the Corda node, the operation will return `404 Not Found` HTTP status code. If no contract
state correspond to a given index, or if the actual contract state object is of a different type,
`400 Bad Request` HTTP status code will be returned.

Note that because Cordaptor knows the contract state type when creating this endpoint, it can generate
fully typed JSON Schema definitions, which means any client code generated from the schema will be
typesafe.

### Vault query endpoint

Each concrete contract state class in CorDapps available for Cordaptor's introspection will have a
vault query endpoint created. Contract state classes extend `net.corda.core.contracts.ContractState` or
any of its subclasses.

Vault query endpoint allows complex queries to be made to retrieve consumer and/or unconsumed contract states
from the Corda vault.

The endpoint resource URLs are constructed using the following template:  
`/node/<cordapp short name>/<contract state class simple name>/query`, where  
_cordapp short name_ is the value of **Cordapp-Workflow-Name** manifest attribute from the CorDapp JAR file
(unless [overridden](./configuration.md#configuring-cordapps)), and  
_contract state class simple name_ is the name of the contract state class without its package.

E.g. for `tech.b180.ref_cordapp.SimpleLinearState` contract state class defined in **reference-cordapp** module,
a valid contract state query endpoint URL would be `/node/reference/SimpleLinearState/query`.

The endpoint accepts both HTTP GET and HTTP POST requests. The former accepts parameters in its query string,
and the latter requires a request payload with a JSON object containing fields corresponding to the parameters.

The following parameters are supported by the operation:
* `pageNumber` (integer, optional) zero-based page number to return or 0 by default
* `pageSize` (integer, optional) number of states to return per results page, or default max page size
* `stateStatus` (enum, optional) approach to querying consumed states: `only`, `include`, or `ignore` (default)
* `linearStateUUIDs` (list of UUIDs as string, optional) UUIDs uniquely identifying instances of linear states
* `linearStateExternalIds` (list of strings, optional) external identifiers given to linear states
* `ownerNames` (list of strings, optional) X.500 names for owners for fungible states
* `participantNames` (list of strings, optional) X.500 names for participants in contract states
* `notaryNames` (list of strings, optional) X.500 names of notaries who approved transactions producing the states
* `recordedTimeIsAfter` (ISO timestamp as string, optional) will not return any states added to the vault before this 
* `consumedTimeIsAfter` (ISO timestamp as string, optional) will not return any states consumed before this 
* `sortCriteria` (string, optional) how to sort the results, default is unsorted.

Sort criteria is important for consistent results when using `pageNumber` and `pageSize` parameters. The following
are the accepted sort criteria:
* `stateRef`
* `stateRefTxId`
* `stateRefIndex`
* `notary` (encoded as X.500 name)
* `contractStateClassName`
* `stateStatus`
* `recordedTime`
* `consumedTime`
* `lockId`
* `constraintType`
* `uuid` (from linear states)
* `externalId` (from linear states)
* `quantity` (from fungible states)
* `issuerRef` (from fungible states)

The operation will return `200 OK` HTTP response and a payload containing a page of contract states
corresponding to the query. The following is a typical response for the operation:

```json5
{
  "stateTypes": "UNCONSUMED",
  "states": [ /* Corresponds to a list of object of type net.corda.core.contracts.StateAndRef<T> */
    {
      "ref": {
        "txhash": "FF0D5832026786635B5EC0E3C8CBD5C7AFF275D7FE19DEAD6D50F5DB9DBB380F",  // creating transaction id
        "index": 0 // index of the output state within the creating transaction
      },
      "state": { /* Corresponds to an instance of net.corda.core.contracts.TransactionState<T> */
        "constraint": { /* Corresponds to the */ },
        "contract": "tech.b180.ref_cordapp.TrivialContract", // contract class governing the evolution of the state
        "data": { /* Actual contract state object */ },
        "notary": { "name": "O=Notary, L=London, C=GB" }
      }
    },
    { /* .. */ },
    { /* .. */ },
    { /* .. */ }
  ],
  "statesMetadata": [ /* Corresponds to a list of object of type net.corda.core.node.services.Vault.StateMetadata */ ],
  "totalStatesAvailable": 4 // number of states matching given criteria in the vault, regardless of the page size
}
```

Note that because Cordaptor knows the contract state type when creating this endpoint, it can generate
fully typed JSON Schema definitions, which means any client code generated from the schema will be
typesafe.

## Logging

Cordaptor can create extensive logging output if configured to do so, which may come handy troubleshooting
unexpected behaviour. Cordaptor uses log4j 2.x via SLF4J adapter (alongside Corda itself), which could be
configured to a different logging level. In standalone Cordaptor by default the configuration file is located at
`<cordaptor home>/conf/log4j2.properties`. For embedded Cordaptor bundle, it uses the same log4j configuration
as the Corda node itself.