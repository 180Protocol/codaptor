package tech.b180.cordaptor.rest

import tech.b180.cordaptor.shaded.javax.json.*
import tech.b180.cordaptor.shaded.javax.json.stream.JsonGenerator

fun <T: Any> JsonObjectBuilder.addObject(key: String, obj: T, block: JsonObjectBuilder.(T) -> Unit): JsonObjectBuilder
    = add(key, Json.createObjectBuilder().also { it.block(obj) }.build())

fun JsonObjectBuilder.addObject(key: String, block: JsonObjectBuilder.() -> Unit): JsonObjectBuilder
    = add(key, Json.createObjectBuilder().apply(block).build())

fun JsonObjectBuilder.addModifiedObject(key: String, obj: JsonObject, block: JsonObjectBuilder.() -> Unit): JsonObjectBuilder
    = add(key, Json.createObjectBuilder(obj).apply(block).build())

fun <T: Any> JsonObjectBuilder.addObjectForEach(
    key: String, iterable: Iterable<T>, block: JsonObjectBuilder.(T) -> Unit): JsonObjectBuilder {

  val arrayBuilder = Json.createArrayBuilder()
  for (obj in iterable) {
    val jsonObject = Json.createObjectBuilder().also { it.block(obj) }.build()
    arrayBuilder.add(jsonObject)
  }
  return add(key, arrayBuilder.build())
}

fun JsonObjectBuilder.addArray(key: String, block: JsonArrayBuilder.() -> Unit): JsonObjectBuilder =
    add(key, Json.createArrayBuilder().apply(block).build())

fun JsonArrayBuilder.addObject(block: JsonObjectBuilder.() -> Unit): JsonArrayBuilder =
    add(Json.createObjectBuilder().apply(block).build())

/**
 * Note this only supports map of primitive types
 *
 * @throws IllegalArgumentException non-primitive type was encountered
 */
fun Map<String, Any>.asJsonObject(): JsonObject {
  return Json.createObjectBuilder(this).build()
}

/**
 * Note this only supports collections of primitive types.
 *
 * @throws IllegalArgumentException non-primitive type was encountered
 */
fun Collection<Any>.asJsonArray(): JsonArray {
  return Json.createArrayBuilder(this).build()
}

/**
 * Helper method allowing [JsonSerializer] to be used in a fluent way
 */
fun <T: Any> JsonGenerator.writeSerializedObject(
    serializer: JsonSerializer<T>,
    obj: T): JsonGenerator {

  serializer.toJson(obj, this)
  return this
}
