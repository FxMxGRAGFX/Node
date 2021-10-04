package dev.cougar.node

import com.google.gson.JsonParser
import dev.cougar.node.packet.Packet
import dev.cougar.node.packet.handler.IncomingPacketHandler
import dev.cougar.node.packet.handler.PacketExceptionHandler
import dev.cougar.node.packet.listener.PacketListener
import dev.cougar.node.packet.listener.PacketListenerData
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.ForkJoinPool

@Suppress("USELESS_ELVIS", "unused", "DEPRECATION")
class Node(private val channel: String, host: String?, port: Int, password: String?) {
    private val jedisPool: JedisPool
    private var jedisPubSub: JedisPubSub? = null
    private val packetListeners: MutableList<PacketListenerData>
    private val idToType: MutableMap<Int, Class<*>> = HashMap()
    private val typeToId: MutableMap<Class<*>, Int> = HashMap()
    @JvmOverloads
    fun sendPacket(packet: Packet, exceptionHandler: PacketExceptionHandler? = null) {
        try {
            val `object` = packet.serialize()
                ?: throw IllegalStateException("Packet cannot generate null serialized data")
            jedisPool.resource.use { jedis ->
                println("[Node] Attempting to publish packet..")
                try {
                    jedis.publish(channel, packet.id().toString() + ";" + `object`.toString())
                    println("[Node] Successfully published packet..")
                } catch (ex: Exception) {
                    println("[Node] Failed to publish packet..")
                    ex.printStackTrace()
                }
            }
        } catch (e: Exception) {
            exceptionHandler?.onException(e)
        }
    }

    fun buildPacket(id: Int): Packet? {
        if (!idToType.containsKey(id)) {
            return null
        }
        try {
            return idToType[id]!!.newInstance() as Packet
        } catch (e: Exception) {
            e.printStackTrace()
        }
        throw IllegalStateException("Could not create new instance of packet type")
    }

    fun registerPacket(clazz: Class<*>) {
        try {
            val id = clazz.getDeclaredMethod("id").invoke(clazz.newInstance(), null) as Int
            check(!(idToType.containsKey(id) || typeToId.containsKey(clazz))) { "A packet with that ID has already been registered" }
            idToType[id] = clazz
            typeToId[clazz] = id
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun registerListener(packetListener: PacketListener) {
        for (method in packetListener.javaClass.declaredMethods) {
            if (method.getDeclaredAnnotation(IncomingPacketHandler::class.java) != null) {
                var packetClass: Class<*>? = null
                if (method.parameters.isNotEmpty()) {
                    if (Packet::class.java.isAssignableFrom(method.parameters[0].type)) {
                        packetClass = method.parameters[0].type
                    }
                }
                if (packetClass != null) {
                    packetListeners.add(PacketListenerData(packetListener, method, packetClass))
                }
            }
        }
    }

    private fun setupPubSub() {
        println("[Node] Setting up PubSup..")
        jedisPubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                if (channel.equals(this@Node.channel, ignoreCase = true)) {
                    try {
                        val args = message.split(";".toRegex()).toTypedArray()
                        val id = Integer.valueOf(args[0])
                        val packet = buildPacket(id)
                        if (packet != null) {
                            packet.deserialize(PARSER.parse(args[1]).asJsonObject)
                            for (data in packetListeners) {
                                if (data.matches(packet)) {
                                    data.method.invoke(data.instance, packet)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[Node] Failed to handle message")
                        e.printStackTrace()
                    }
                }
            }
        }
        ForkJoinPool.commonPool().execute {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.subscribe(jedisPubSub, channel)
                    println("[Node] Successfully subscribing to channel..")
                }
            } catch (exception: Exception) {
                println("[Node] Failed to subscribe to channel..")
                exception.printStackTrace()
            }
        }
    }

    companion object {
        private val PARSER = JsonParser()
    }

    init {
        packetListeners = ArrayList()
        jedisPool = JedisPool(host, port)
        if (password != null && password != "") {
            jedisPool.resource.use { jedis ->
                jedis.auth(password)
                println("[Node] Authenticating..")
            }
        }
        setupPubSub()
    }
}