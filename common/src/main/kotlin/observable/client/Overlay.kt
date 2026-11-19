package observable.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

object Overlay {
    data class EntityEntry(val id: Int, val rate: Double)
    data class BlockEntry(val pos: BlockPos, val rate: Double)

    var enabled = true
    private var entities = emptyList<EntityEntry>()
    private var blockMap = emptyMap<ChunkPos, List<BlockEntry>>()

    @Synchronized
    fun loadSync(level: ClientLevel? = Minecraft.getInstance().level) {
        val data = ObservableClient.results
        if (data == null || level == null) {
            entities = emptyList()
            blockMap = emptyMap()
            return
        }

        val dimension = level.dimension().identifier()
        fun rate(rate: Double, ticks: Int): Double =
            if (ClientSettings.normalized && data.ticks > 0) rate * ticks / data.ticks else rate

        entities = data.entities[dimension].orEmpty()
            .mapNotNull { entry -> entry.entityId?.let { EntityEntry(it, rate(entry.rate, entry.ticks)) } }
            .filter { it.rate >= ClientSettings.minRate }
            .sortedByDescending { it.rate }

        blockMap = data.blocks[dimension].orEmpty()
            .map { BlockEntry(it.position, rate(it.rate, it.ticks)) }
            .filter { it.rate >= ClientSettings.minRate }
            .sortedByDescending { it.rate }
            .groupBy { ChunkPos.containing(it.pos) }
    }

    @Synchronized
    fun render(level: ClientLevel, camera: Camera, partialTicks: Float) {
        if (!enabled || ObservableClient.results == null) return

        val cameraPos = camera.position()
        val cameraChunk = ChunkPos.containing(camera.blockPosition())
        val maxBlockDist = ClientSettings.maxBlockDist.coerceAtLeast(0).toDouble()
        val maxBlockDistSquared = maxBlockDist * maxBlockDist
        val chunkRadius = (maxBlockDist / 16).toInt() + 1

        blockMap.asSequence()
            .filter { (chunk, _) ->
                chunk.x() in (cameraChunk.x() - chunkRadius)..(cameraChunk.x() + chunkRadius) &&
                    chunk.z() in (cameraChunk.z() - chunkRadius)..(cameraChunk.z() + chunkRadius)
            }
            .flatMap { it.value.asSequence() }
            .filter { cameraPos.distanceToSqr(Vec3.atCenterOf(it.pos)) <= maxBlockDistSquared }
            .take(ClientSettings.maxBlockCount.coerceAtLeast(0))
            .forEach { entry ->
                val color = heatColor(entry.rate, 75)
                Gizmos.cuboid(entry.pos, GizmoStyle.fill(color)).setAlwaysOnTop()
                Gizmos.billboardText(timingLabel(entry.rate), Vec3.atCenterOf(entry.pos), TextGizmo.Style.whiteAndCentered())
                    .setAlwaysOnTop()
            }

        val maxEntityDist = ClientSettings.maxEntityDist.coerceAtLeast(0).toDouble()
        val maxEntityDistSquared = maxEntityDist * maxEntityDist
        entities.asSequence()
            .mapNotNull { entry ->
                val entity = level.getEntity(entry.id)?.takeUnless { it.isRemoved } ?: return@mapNotNull null
                val position = entity.getPosition(partialTicks)
                if (cameraPos.distanceToSqr(position) > maxEntityDistSquared) return@mapNotNull null
                val label = timingLabel(entry.rate) + if (entity.isAlive) "" else " [X]"
                Triple(label, position.add(0.0, entity.bbHeight + 0.33, 0.0), heatColor(entry.rate, 255))
            }
            .take(ClientSettings.maxEntityCount.coerceAtLeast(0))
            .forEach { (label, position, color) ->
                Gizmos.billboardText(label, position, TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop()
            }
    }

    private fun timingLabel(rate: Double) = "${(rate / 1000).roundToInt()} μs/t"

    private fun heatColor(rate: Double, alpha: Int): Int {
        val red = ((rate / 100_000.0).coerceIn(0.0, 1.0) * 255).roundToInt()
        return (alpha shl 24) or (red shl 16) or ((255 - red) shl 8)
    }
}
