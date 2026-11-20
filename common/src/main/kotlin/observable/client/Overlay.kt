package observable.client

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

object Overlay {
    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        companion object {
            fun fromNanos(rateNanos: Double): Color {
                val micros = rateNanos / 1000.0
                return Color(micros)
            }
        }

        constructor(
            rateMicros: Double
        ) : this(
            (rateMicros / 100.0 * 255).roundToInt().coerceIn(0, 255),
            ((100.0 - rateMicros) / 100.0 * 255).roundToInt().coerceIn(0, 255),
            0,
            (rateMicros / 100.0 * 255).roundToInt().coerceIn(20, 100)
        )

        val hex: Int =
            with(this) {
                val red = if (r > g) 0xFFu else (255 * r / g).toUInt()
                val green = if (g > r) 0xFFu else (255 * g / r).toUInt()
                (red shl 16) or (green shl 8) or (0xFFu shl 24)
            }
                .toInt()
    }

    sealed class Entry(val color: Color) {
        data class EntityEntry(val entityId: Int, val rate: Double) : Entry(Color.fromNanos(rate))
        data class BlockEntry(val pos: BlockPos, val rate: Double) : Entry(Color.fromNanos(rate))
    }

    var enabled = true
    private var entities = emptyList<Entry.EntityEntry>()
    private var blocks = emptyList<Entry.BlockEntry>()
    private var blockMap = emptyMap<ChunkPos, List<Entry.BlockEntry>>()
    private var blockMesh: BlockOverlayMesh? = null
    private var meshCameraPosition: Vec3? = null

    fun load(lvl: ClientLevel? = null) {
        val data = ObservableClient.results ?: return
        val level = lvl ?: Minecraft.getInstance().level ?: return
        val levelLocation = level.dimension().identifier()
        val ticks = data.ticks
        val norm = ClientSettings.normalized
        entities =
            data.entities[levelLocation]
                ?.map {
                    Entry.EntityEntry(
                        it.entityId!!,
                        it.rate * (if (norm) it.ticks.toDouble() / ticks else 1.0)
                    )
                }
                .orEmpty()
                .filter { it.rate >= ClientSettings.minRate }
                .sortedByDescending { it.rate }

        blocks =
            data.blocks[levelLocation]
                ?.map {
                    Entry.BlockEntry(
                        it.position,
                        it.rate * (if (norm) it.ticks.toDouble() / ticks else 1.0)
                    )
                }
                ?.filter { it.rate >= ClientSettings.minRate }
                .orEmpty()
        blockMap = blocks.groupBy { ChunkPos.containing(it.pos) }
        releaseBlockMesh()
    }

    inline fun loadSync(lvl: ClientLevel? = null) = synchronized(this) { this.load(lvl) }

    @Synchronized
    fun releaseBlockMesh() {
        blockMesh?.let { mesh -> Minecraft.getInstance().execute { mesh.close() } }
        blockMesh = null
        meshCameraPosition = null
    }

    @Synchronized
    fun hasBlockGeometry() = enabled && ObservableClient.results != null && blocks.isNotEmpty()

    @Synchronized
    fun renderBlocks(target: RenderTarget, camera: CameraRenderState) {
        if (!hasBlockGeometry()) return
        val previousPosition = meshCameraPosition
        if (previousPosition == null || previousPosition.distanceToSqr(camera.pos) > 1_000_000) {
            releaseBlockMesh()
            val nearbyBlocks = blocks.filter { it.pos.distSqr(camera.blockPos) < 1_440_000 }
            if (nearbyBlocks.isNotEmpty()) {
                blockMesh = BlockOverlayMesh.upload(nearbyBlocks, camera.blockPos)
            }
            meshCameraPosition = camera.pos
        }
        blockMesh?.draw(target, camera.pos)
    }

    @Synchronized
    fun render(level: ClientLevel, camera: Camera, partialTicks: Float) {
        if (!enabled || ObservableClient.results == null) return

        val player = Minecraft.getInstance().player ?: return
        val chunk = ChunkPos.containing(player.blockPosition())
        val radius = (ClientSettings.maxBlockDist / 16).coerceAtLeast(2)
        val maxDist = ClientSettings.maxBlockDist * ClientSettings.maxBlockDist
        for (x in (chunk.x() - radius)..(chunk.x() + radius)) {
            for (z in (chunk.z() - radius)..(chunk.z() + radius)) {
                blockMap[ChunkPos(x, z)]?.forEach { entry ->
                    if (camera.blockPosition().distSqr(entry.pos) < maxDist) {
                        drawLabel(timingLabel(entry.rate), Vec3.atCenterOf(entry.pos), -1)
                    }
                }
            }
        }

        val maxEntityIndex = ClientSettings.maxEntityCount - 1
        for ((index, entry) in entities.withIndex()) {
            if (index > maxEntityIndex) break
            val entity = level.getEntity(entry.entityId) ?: continue
            if (entity.isRemoved) continue

            val position = entity.getPosition(partialTicks)
            if (camera.position().distanceTo(position) > ClientSettings.maxEntityDist) continue
            val label = timingLabel(entry.rate) + if (entity.isAlive) "" else " [X]"
            drawLabel(label, position.add(0.0, entity.bbHeight + 0.33, 0.0), entry.color.hex)
        }
    }

    private fun timingLabel(rate: Double) = "${(rate / 1000).roundToInt()} μs/t"

    private fun drawLabel(label: String, position: Vec3, color: Int) {
        // The gizmo renderer divides this by 16, so 0.4 preserves the original 0.025 world scale.
        val style = TextGizmo.Style.forColorAndCentered(color).withScale(0.4F)
        Gizmos.billboardText(label, position, style).setAlwaysOnTop()
    }
}
