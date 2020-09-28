# 1. Onboarding and setup

1. Obtaining an OpenAPI definition file for the API from a running Cordaptor instance
2. Accessing SwaggerUI for the API from a running Cordaptor instance
3. Obtaining an OpenAPI definition file for the API via Cordaptor CLI
4. Securing HTTP connection to Cordaptor via TLS on localhost  
   Issuing a self-signed certificate for localhost
5. Configuring Corda RPC credentials for standalone Cordaptor to use
6. Querying flow's progress tracker steps via an HTTP GET request
7. Querying flow's invocation parameters and result schema via an HTTP GET request
8. Querying contract state's schema via an HTTP GET request

# 2. Querying node's vault for contract states

1. Querying the vault for a contract state by a stateref via an HTTP GET request
2. Querying the vault for linear states by UUID with pagination via an HTTP GET request  
   Modeled on LinearStateQueryCriteria
3. Querying the vault for ownable states by the owner with pagination via an HTTP GET request
4. Querying the vault for queryable states by schema fields with pagination via an HTTP GET request
5. Querying the vault for a transaction by txhash via an HTTP GET request
6. Querying the vault for the number of states satisfying given criteria
7. Querying the vault for the total amount of fungible states satisfying given criteria

# 3. Initiating flows and interacting with state machine

1. Initiating a flow via an HTTP POST request and optionally waiting for completion  
   Specify max time to wait in the request, return 202 Accepted+runid if not completed  
   This will make AWS API gateway problematic, as maximum timeout is 29 seconds
2. Initiating a flow via an HTTP POST request and obtaining a flow runid
3. Querying the state and obtaining the result of a flow by runid via an HTTP GET request  
   For flows in-flight: progress stage  
   For completed flows: txhash if created

# 4. Real-time interactions with the node

1. Subscribing for vault updates with a given filters (as in section 2) via a websocket
2. Initiating a flow and receiving status updates via a websocket

# 5. Querying information about the node

1. Getting node status and diagnostics via an HTTP GET request

# 6. Non-functionals

1. Allowing to launch a standalone Cordaptor instance without Corda node running  
   Useful for launching in docker-compose, as node takes some time to open an RPC port
2. Reconnecting to Corda node when connection drops  
   Supported failover methods: list of hostnames, DNS failovers
3. Using Redis+local cache as a data-grid to respond to API queries  
   Redis master/slave or cluster to lookup states, transactions, flow updates/results
   Cordaptor instance populates the cache via vault tracking
4. Allow standalone Cordaptor instances clustering  
   Only one Cordaptor instance is listening to the node via RPC vaultTrackBy(...) to reduce node load  
   Active listener instance maintains a lock using Redis, and broadcasts a heartbeat using Redis pub/sub  
   Absence of a heatbeat will make another node to step in and try to acquire a lock
   Websocket subscriptions use Redis pub/sub channels
