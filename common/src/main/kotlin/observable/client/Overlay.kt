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
    }

    inline fun loadSync(lvl: ClientLevel? = null) = synchronized(this) { this.load(lvl) }

    @Synchronized
    fun render(level: ClientLevel, camera: Camera, partialTicks: Float) {
        if (!enabled || ObservableClient.results == null) return

        for (entry in blocks) {
            val (r, g, b, a) = entry.color
            val color = (a shl 24) or (r shl 16) or (g shl 8) or b
            Gizmos.cuboid(entry.pos, GizmoStyle.fill(color)).setAlwaysOnTop()
        }

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
            if (entity.isRemoved || (entity == player && entity.deltaMovement.lengthSqr() > 0.01)) continue

            var position = entity.position()
            if (camera.position().distanceTo(position) > ClientSettings.maxEntityDist) continue
            var label = timingLabel(entry.rate)
            if (entity.isAlive) {
                val motion = entity.deltaMovement
                position = position.add(Vec3(motion.x, motion.y.coerceAtLeast(0.0), motion.z).scale(partialTicks.toDouble()))
            } else {
                label += " [X]"
            }
            drawLabel(label, position.add(0.0, entity.bbHeight + 0.33, 0.0), entry.color.hex)
        }
    }

    private fun timingLabel(rate: Double) = "${(rate / 1000).roundToInt()} μs/t"

    private fun drawLabel(label: String, position: Vec3, color: Int) {
        Gizmos.billboardText(label, position, TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop()
    }
}
