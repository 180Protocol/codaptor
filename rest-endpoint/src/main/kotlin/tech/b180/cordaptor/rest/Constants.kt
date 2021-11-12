package tech.b180.cordaptor.rest

// set of constants representing API operations when performing API security engine checks
const val OPERATION_GET_NODE_INFO = "getNodeInfo"
const val OPERATION_GET_NODE_VERSION = "getNodeVersion"
const val OPERATION_INITIATE_FLOW = "initiateFlow"
const val OPERATION_GET_FLOW_SNAPSHOT = "getFlowSnapshot"
const val OPERATION_GET_STATE_BY_REF = "getStateByRef"
const val OPERATION_QUERY_STATES = "queryStates"
const val OPERATION_GET_TX_BY_HASH = "getTransactionByHash"
const val OPERATION_UPLOAD_NODE_ATTACHMENT = "uploadNodeAttachment"

// constants used as Koin qualifiers for security configuration factories
const val SECURITY_CONFIGURATION_NONE = "none"
const val SECURITY_CONFIGURATION_API_KEY = "apiKey"