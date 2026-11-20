package observable.net

import dev.architectury.networking.NetworkManager
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@OptIn(ExperimentalSerializationApi::class)
class BetterChannel(id: Identifier) {
    companion object {
        val LOGGER = LogManager.getLogger("ObservableNet")
        const val PACKET_SIZE = 1_000_000
    }

    val s2cLocation: Identifier = id.withSuffix("-s2c")
    val c2sLocation: Identifier = id.withSuffix("-c2s")

    @Serializable
    data class PartialPacketBegin(val id: Long, val type: String)

    @Serializable
    class PartialPacketData(val id: Long, val data: ByteArray, val index: Int, val length: Int)

    private class PartialPacketAssembler(val type: String) {
        val packets = mutableMapOf<Int, ByteArray>()

        fun assemble(): ByteArray {
            val bytes = ByteArrayOutputStream()
            packets.toSortedMap().values.forEach { bytes.write(it) }
            return bytes.toByteArray()
        }
    }

    private val partialPackets = mutableMapOf<Long, PartialPacketAssembler>()
    val handlers = mutableMapOf<String, (ByteArray, NetworkManager.PacketContext) -> Unit>()

    class SerializedPayload(val className: String, val data: ByteArray, val location: Identifier) : CustomPacketPayload {
        override fun type() = CustomPacketPayload.Type<SerializedPayload>(location)
    }

    private fun codec(location: Identifier) = object : StreamCodec<RegistryFriendlyByteBuf, SerializedPayload> {
        override fun decode(buf: RegistryFriendlyByteBuf) = SerializedPayload(buf.readUtf(), buf.readByteArray(), location)

        override fun encode(buf: RegistryFriendlyByteBuf, payload: SerializedPayload) {
            buf.writeUtf(payload.className)
            buf.writeByteArray(payload.data)
        }
    }

    init {
        val serverToClient = CustomPacketPayload.Type<SerializedPayload>(s2cLocation)
        val clientToServer = CustomPacketPayload.Type<SerializedPayload>(c2sLocation)
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(serverToClient, codec(s2cLocation))
        } else {
            NetworkManager.registerReceiver(NetworkManager.Side.S2C, serverToClient, codec(s2cLocation), ::receive)
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, clientToServer, codec(c2sLocation), ::receive)

        register { packet: PartialPacketBegin, _ ->
            LOGGER.info("Received starting packet ${packet.id} for ${packet.type}")
            if (handlers.containsKey(packet.type)) {
                partialPackets[packet.id] = PartialPacketAssembler(packet.type)
            } else {
                LOGGER.warn("Could not find mapping for ${packet.type}")
            }
        }
        register { packet: PartialPacketData, context ->
            LOGGER.info("Received packet ${packet.index + 1}/${packet.length} of id ${packet.id}")
            partialPackets[packet.id]?.let { assembler ->
                assembler.packets[packet.index] = packet.data
                if (packet.length == assembler.packets.size) {
                    LOGGER.info("Assembling packet ${assembler.type}")
                    handlers[assembler.type]?.invoke(assembler.assemble(), context)
                    partialPackets.remove(packet.id)
                }
            }
        }
    }

    private fun receive(payload: SerializedPayload, context: NetworkManager.PacketContext) {
        handlers[payload.className]?.invoke(payload.data, context)
    }

    inline fun <reified T> register(noinline consumer: (T, NetworkManager.PacketContext) -> Unit) {
        val name = T::class.java.name
        LOGGER.info("Registering ${T::class.java}")
        handlers[name] = handler@{ bytes, context ->
            val packet = try {
                ProtoBuf.decodeFromByteArray<T>(bytes)
            } catch (e: Exception) {
                LOGGER.warn("Error decoding packet!", e)
                return@handler
            }
            consumer(packet, context)
        }
        LOGGER.info("Registered ${T::class.java}")
    }

    inline fun <reified T> sendToPlayers(players: Iterable<ServerPlayer>, msg: T) {
        NetworkManager.sendToPlayers(
            players.filter { NetworkManager.canPlayerReceive(it, s2cLocation) },
            SerializedPayload(T::class.java.name, ProtoBuf.encodeToByteArray(msg), s2cLocation)
        )
    }

    inline fun <reified T> sendToPlayer(player: ServerPlayer, msg: T) = sendToPlayers(listOf(player), msg)

    inline fun <reified T> sendToPlayersSplit(players: Iterable<ServerPlayer>, msg: T) {
        val bytes = ByteArrayInputStream(ProtoBuf.encodeToByteArray(msg))
        val id = UUID.randomUUID().leastSignificantBits
        sendToPlayers(players, PartialPacketBegin(id, T::class.java.name))
        val length = bytes.available() / PACKET_SIZE + (bytes.available() % PACKET_SIZE).coerceAtMost(1)
        var index = 0
        while (bytes.available() > 0) {
            val chunk = bytes.readNBytes(PACKET_SIZE)
            sendToPlayers(players, PartialPacketData(id, chunk, index++, length))
        }
    }

    inline fun <reified T> sendToServer(msg: T) {
        if (NetworkManager.canServerReceive(c2sLocation)) {
            NetworkManager.sendToServer(SerializedPayload(T::class.java.name, ProtoBuf.encodeToByteArray(msg), c2sLocation))
        }
    }
}
