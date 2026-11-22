package observable.client

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import observable.Observable
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.Optional
import java.util.OptionalInt

internal class BlockOverlayMesh private constructor(
    private val buffer: GpuBuffer,
    private val origin: BlockPos,
    private val indexCount: Int
) {
    companion object {
        private val pipeline = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(Observable.MOD_ID, "blocks"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(Optional.empty<DepthStencilState>())
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .build()

        // Each corner uses one bit per axis, in the order XYZ. Four corners form each outward-facing quad.
        private val corners = intArrayOf(
            2, 3, 7, 6,
            2, 6, 4, 0,
            7, 3, 1, 5,
            3, 2, 0, 1,
            5, 4, 6, 7,
            4, 5, 1, 0
        )

        fun upload(blocks: List<Overlay.Entry.BlockEntry>, cameraBlockPos: BlockPos): BlockOverlayMesh {
            // Minecraft reuses the camera position, but the vertex offsets stay relative to this origin.
            val origin = cameraBlockPos.immutable()
            ByteBufferBuilder(4096).use { builder ->
                val vertices = BufferBuilder(builder, pipeline.vertexFormatMode, pipeline.vertexFormat)
                for (entry in blocks) {
                    val pos = entry.pos
                    val color = entry.color
                    val x = pos.x - origin.x
                    val y = pos.y - origin.y
                    val z = pos.z - origin.z
                    for (corner in corners) {
                        vertices.addVertex(
                            (x + (corner shr 2)).toFloat(),
                            (y + (corner shr 1 and 1)).toFloat(),
                            (z + (corner and 1)).toFloat()
                        ).setColor(color.r, color.g, color.b, color.a)
                    }
                }
                vertices.buildOrThrow().use { mesh ->
                    val buffer = RenderSystem.getDevice().createBuffer(
                        { "Observable block overlay vertices" }, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()
                    )
                    return BlockOverlayMesh(buffer, origin, mesh.drawState().indexCount())
                }
            }
        }
    }

    fun draw(target: RenderTarget, camera: Vec3) {
        val colorTarget = target.colorTextureView ?: return
        val modelView = Matrix4f(RenderSystem.getModelViewMatrix()).translate(
            (origin.x - camera.x).toFloat(),
            (origin.y - camera.y).toFloat(),
            (origin.z - camera.z).toFloat()
        )
        val transform = RenderSystem.getDynamicUniforms().writeTransform(
            modelView, Vector4f(1F), Vector3f(), Matrix4f()
        )
        val indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)
        val indexBuffer = indices.getBuffer(indexCount)
        RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass({ "Observable block overlay" }, colorTarget, OptionalInt.empty())
            .use { pass ->
                pass.setPipeline(pipeline)
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("DynamicTransforms", transform)
                pass.setVertexBuffer(0, buffer)
                pass.setIndexBuffer(indexBuffer, indices.type())
                pass.drawIndexed(0, 0, indexCount, 1)
            }
    }

    fun close() = RenderSystem.queueFencedTask(buffer::close)
}
