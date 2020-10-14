# API overview

Principles:
1. All metadata is accessible via the API
2. Simple operations are simple to invoke

Reg 1.1 Obtaining an OpenAPI definition file for the API from a running Cordaptor instance
```
GET /openapi.json
```

Req 1.2 Accessing SwaggerUI for the API from a running Cordaptor instance
```
GET /swagger-ui.html
```

Req 1.6 Querying flow's progress tracker steps via an HTTP GET request
Req 1.7 Querying flow's invocation parameters and result schema via an HTTP GET request
```
GET /node/{flowClassName}
```
Returns flow metadata:
* Progress tracker steps
* Schema for parameters
* Schema for result

Req 1.8 Querying contract state's schema via an HTTP GET request
```
GET /node/{stateClassName}
```
Returns contract state metadata:
* Contract state FQCN
* Schema describing an instance

Req 2.1 Querying the vault for a contract state by a stateref via an HTTP GET request
```
GET /node/{stateClassName}/{stateRef}
```
stateClassName - FQCN, FQCN of a supertype, simple name, simple name of a supertype (e.g. ContractState)
Canonical URL when used with FQCN, other queries return 303 See Other and a Location URL with FQDN

Req 2.2 Querying the vault for linear states by UUID with pagination via an HTTP GET request
```
GET /node/states?uuid={uuid}&...
```

Req 2.3 Querying the vault for ownable states by the owner with pagination via an HTTP GET request
```
GET /node/states?owner={x500name}&...
```

Req 2.4 Querying the vault for queryable states by schema fields with pagination via an HTTP GET request
```
GET /node/states?{fieldName}={value}
```

Req 2.5 Querying the vault for a transaction by txhash via an HTTP GET request
```
GET /node/tx/{txhash}
```
Canonical URL

Req 2.6 Querying the vault for the number of states satisfying given criteria
```
GET /node/statesCount?...
```

Req 2.7 Querying the vault for the total amount of fungible states satisfying given criteria
```
GET /node/statesTotalAmount?...
```

Req 3.1 Initiating a flow via an HTTP POST request and optionally waiting for completion
Req 3.2 Initiating a flow via an HTTP POST request and obtaining a flow runid
```
POST /node/{flowClassName}?wait={seconds}
```
Returns 200 OK if completed within the given timeout, body contains completed flow status and result if applicable
Returns 202 Accepted if not completed within the given timeout, body contains flow runid + Location
Returns 500 when the flow has failed during the specified timeout period

Req 3.3 Querying the state and obtaining the result of a flow by runid via an HTTP GET request
```
GET /node/flows/{runId}
```
Returns 200 OK, and the body provides information about flow status, progress, result, and created transactions

Req 4.1 Subscribing for vault updates with a given filters (as in section 2) via a websocket
```
ws(s)://{cordaptorBaseUrl}/node
->
{
  "command": "SUBSCRIBE",
  "filters": [ ... ]
}
<-
{
  "message": "UPDATE",
  "payload": { ... }
}
```

Req 4.2 Initiating a flow and receiving status updates via a websocket
```
ws(s)://{cordaptorBaseUrl}/node
->
{
  "command": "START",
  "flowClassName": "FQCN",
  "trackProgress": true,
  "parameters": [ ... ]
}
<-
{
  "message": "PROGRESS",
  "payload": { ... }
}
{
  "message": "ERROR",
  "payload": { ... }
}
{
  "message": "COMPLETION",
  "payload": { ... }
}
```

Req 5.1 Getting node status and diagnostics via an HTTP GET request
```
GET /node/info
```

## Error handling
* 500 code if flow has failed
* 503 if connection to the node has failed

## Vault queries
If multiple values given to a particular field, value containing at least one of them will be returned 
* type - one or more of FQCN or simple name, actual type or a supertype
* uuid - one or more of UUID for LinearStates
* externalId - one or more of externalIds for LinearStates
* owner - one or more of X500 names for OwnableStates
* participant - one or more of X500 names
* notary - one or more of X500 names
Special parameters accepting only one value:
* consumed - one of ignore (default), include, or only
* pageNumber - above-zero integer, defaults to 1
* pageSize - above-zero integer, defaults to Corda DEFAULT_PAGE_SIZE (200 for 4.5)
In addition, all schema fields declared in queryable state schema could be used in filtering.
Different syntax forms apply:
* For exact comparison add suffix 'Is'
* For substring comparison add suffix 'BeginsWith'
For example, if schema contains a field called 'shipmentCode', for exact filtering specify keyword 'shipmentCodeIs',
and for substring filtering it must be 'shipmentCodeBeginsWith'.
