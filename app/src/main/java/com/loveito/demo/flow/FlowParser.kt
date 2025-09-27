package com.loveito.demo.flow

import com.google.gson.*
import java.lang.reflect.Type

object FlowParser {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Node::class.java, NodeDeserializer())
        .create()

    fun parse(json: String): SeizureAssistantDefinition = gson.fromJson(json, SeizureAssistantDefinition::class.java)
}

private class NodeDeserializer : JsonDeserializer<Node> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Node {
        val obj = json.asJsonObject
        val type = obj.get("type").asString
        return when (type) {
            "info" -> context.deserialize<Node.InfoNode>(json, Node.InfoNode::class.java)
            "yes_no" -> context.deserialize<Node.YesNoNode>(json, Node.YesNoNode::class.java)
            "event" -> context.deserialize<Node.EventNode>(json, Node.EventNode::class.java)
            "action" -> context.deserialize<Node.ActionNode>(json, Node.ActionNode::class.java)
            "branch" -> context.deserialize<Node.BranchNode>(json, Node.BranchNode::class.java)
            else -> throw IllegalArgumentException("Tipo de nodo desconocido: $type")
        }
    }
}

