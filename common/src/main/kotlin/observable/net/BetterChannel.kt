package observable.net

import dev.architectury.networking.NetworkManager
import dev.architectury.networking.NetworkManager.Side
import dev.architectury.networking.transformers.SplitPacketTransformer
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import kotlinx.serialization.ExperimentalSerializationApi
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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalSerializationApi::class)
class BetterChannel(id: Identifier) {
    companion object {
        val LOGGER = LogManager.getLogger("ObservableNet")
    }

    val s2cLocation: Identifier = id.withSuffix("-s2c")
    val c2sLocation: Identifier = id.withSuffix("-c2s")
    val handlers = mutableMapOf<String, (ByteArray, NetworkManager.PacketContext) -> Unit>()

    class SerializedPayload(val className: String, val data: ByteArray, val location: Identifier) : CustomPacketPayload {
        override fun type() = CustomPacketPayload.Type<SerializedPayload>(location)
    }

    private fun codec(location: Identifier) = object : StreamCodec<RegistryFriendlyByteBuf, SerializedPayload> {
        override fun decode(buf: RegistryFriendlyByteBuf): SerializedPayload {
            val name = buf.readUtf()
            val data = GZIPInputStream(ByteArrayInputStream(buf.readByteArray())).use { it.readAllBytes() }
            return SerializedPayload(name, data, location)
        }

        override fun encode(buf: RegistryFriendlyByteBuf, payload: SerializedPayload) {
            buf.writeUtf(payload.className)
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).buffered().use { it.write(payload.data) }
            buf.writeByteArray(bytes.toByteArray())
        }
    }

    init {
        val serverToClient = CustomPacketPayload.Type<SerializedPayload>(s2cLocation)
        val clientToServer = CustomPacketPayload.Type<SerializedPayload>(c2sLocation)
        val splitPackets = listOf(SplitPacketTransformer())
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(serverToClient, codec(s2cLocation), splitPackets)
        } else {
            NetworkManager.registerReceiver(Side.S2C, serverToClient, codec(s2cLocation), splitPackets, ::receive)
        }
        NetworkManager.registerReceiver(Side.C2S, clientToServer, codec(c2sLocation), ::receive)
    }

    private fun receive(payload: SerializedPayload, context: NetworkManager.PacketContext) {
        handlers[payload.className]?.invoke(payload.data, context)
    }

    inline fun <reified T> register(noinline consumer: (T, NetworkManager.PacketContext) -> Unit) {
        handlers[T::class.java.name] = { bytes, context -> consumer(ProtoBuf.decodeFromByteArray(bytes), context) }
        LOGGER.info("Registered ${T::class.java}")
    }

    inline fun <reified T> sendToPlayers(players: Iterable<ServerPlayer>, msg: T) {
        NetworkManager.sendToPlayers(
            players.filter { NetworkManager.canPlayerReceive(it, s2cLocation) },
            SerializedPayload(T::class.java.name, ProtoBuf.encodeToByteArray(msg), s2cLocation)
        )
    }

    inline fun <reified T> sendToPlayer(player: ServerPlayer, msg: T) = sendToPlayers(listOf(player), msg)

    inline fun <reified T> sendToPlayersSplit(players: Iterable<ServerPlayer>, msg: T) = sendToPlayers(players, msg)

    inline fun <reified T> sendToServer(msg: T) {
        if (NetworkManager.canServerReceive(c2sLocation)) {
            NetworkManager.sendToServer(SerializedPayload(T::class.java.name, ProtoBuf.encodeToByteArray(msg), c2sLocation))
        }
    }
}
