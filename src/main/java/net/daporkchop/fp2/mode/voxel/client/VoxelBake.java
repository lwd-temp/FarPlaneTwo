/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2021 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * Any persons and/or organizations using this software must include the above copyright notice and this permission notice,
 * provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.fp2.mode.voxel.client;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import net.daporkchop.fp2.client.TexUVs;
import net.daporkchop.fp2.client.gl.object.IGLBuffer;
import net.daporkchop.fp2.client.gl.object.VertexArrayObject;
import net.daporkchop.fp2.client.gl.type.Int2_10_10_10_Rev;
import net.daporkchop.fp2.client.gl.vertex.IVertexAttribute;
import net.daporkchop.fp2.client.gl.vertex.VertexAttributeInterpretation;
import net.daporkchop.fp2.client.gl.vertex.VertexAttributeType;
import net.daporkchop.fp2.client.gl.vertex.VertexFormat;
import net.daporkchop.fp2.compat.vanilla.FastRegistry;
import net.daporkchop.fp2.mode.common.client.BakeOutput;
import net.daporkchop.fp2.mode.voxel.VoxelData;
import net.daporkchop.fp2.mode.voxel.VoxelPos;
import net.daporkchop.fp2.mode.voxel.VoxelTile;
import net.daporkchop.fp2.util.Constants;
import net.daporkchop.fp2.util.SingleBiomeBlockAccess;
import net.daporkchop.fp2.util.datastructure.PointOctree3I;
import net.daporkchop.lib.common.pool.array.ArrayAllocator;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;

import static java.lang.Math.*;
import static net.daporkchop.fp2.client.ClientConstants.*;
import static net.daporkchop.fp2.client.gl.GLCompatibilityHelper.*;
import static net.daporkchop.fp2.client.gl.OpenGL.*;
import static net.daporkchop.fp2.mode.voxel.VoxelConstants.*;
import static net.daporkchop.fp2.util.BlockType.*;
import static net.daporkchop.fp2.util.Constants.*;
import static net.daporkchop.fp2.util.math.MathUtil.*;

/**
 * Shared code for baking voxel geometry.
 *
 * @author DaPorkchop_
 */
@UtilityClass
public class VoxelBake {
    protected static final IVertexAttribute.Int1 ATTRIB_STATE = IVertexAttribute.Int1.builder()
            .alignAndPadTo(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT)
            .type(VertexAttributeType.UNSIGNED_INT)
            .interpretation(VertexAttributeInterpretation.INTEGER)
            .build();

    protected static final IVertexAttribute.Int2 ATTRIB_LIGHT = IVertexAttribute.Int2.builder(ATTRIB_STATE)
            .alignAndPadTo(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT)
            .type(VertexAttributeType.UNSIGNED_BYTE)
            .interpretation(VertexAttributeInterpretation.NORMALIZED_FLOAT)
            .build();

    protected static final IVertexAttribute.Int3 ATTRIB_COLOR = IVertexAttribute.Int3.builder(ATTRIB_LIGHT)
            .alignAndPadTo(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT)
            .reportedComponents(4)
            .type(VertexAttributeType.UNSIGNED_BYTE)
            .interpretation(VertexAttributeInterpretation.NORMALIZED_FLOAT)
            .build();

    protected static final IVertexAttribute.Int3 ATTRIB_POS_LOW = IVertexAttribute.Int3.builder(ATTRIB_COLOR)
            .alignAndPadTo(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT)
            .reportedComponents(4)
            .type(VertexAttributeType.UNSIGNED_BYTE)
            .interpretation(VertexAttributeInterpretation.FLOAT)
            .build();

    protected static final IVertexAttribute.Int4 ATTRIB_POS_HIGH = IVertexAttribute.Int4.builder(ATTRIB_POS_LOW)
            .alignAndPadTo(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT)
            .type(WORKAROUND_AMD_INT_2_10_10_10_REV ? VertexAttributeType.SHORT : VertexAttributeType.INT_2_10_10_10_REV)
            .interpretation(VertexAttributeInterpretation.FLOAT)
            .build();

    protected static final VertexFormat VERTEX_FORMAT = new VertexFormat(ATTRIB_POS_HIGH, max(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT, INT_SIZE));

    public void vertexAttributes(@NonNull IGLBuffer buffer, @NonNull VertexArrayObject vao) {
        FP2_LOG.info("voxel vertex size: {} bytes", VERTEX_FORMAT.size());
        VERTEX_FORMAT.configureVAO(vao, buffer);
    }

    protected static int vertexMapIndex(int dx, int dy, int dz, int i, int edge) {
        int j = CONNECTION_INDICES[i];
        int ddx = dx + ((j >> 2) & 1);
        int ddy = dy + ((j >> 1) & 1);
        int ddz = dz + (j & 1);

        return ((ddx * T_VERTS + ddy) * T_VERTS + ddz) * EDGE_COUNT + edge;
    }

    public void bakeForShaderDraw(@NonNull VoxelPos dstPos, @NonNull VoxelTile[] srcs, @NonNull BakeOutput output, @NonNull ByteBuf verts, @NonNull ByteBuf[] indices) {
        if (srcs[0] == null) {
            return;
        }

        final int level = dstPos.level();
        final int blockX = dstPos.blockX();
        final int blockY = dstPos.blockY();
        final int blockZ = dstPos.blockZ();

        ArrayAllocator<int[]> alloc = ALLOC_INT.get();
        int[] map = alloc.atLeast(cb(T_VERTS) * EDGE_COUNT);
        Arrays.fill(map, 0, cb(T_VERTS) * EDGE_COUNT, -1);

        try {
            /*//step 1: build octrees
            PointOctree3I lowOctree = buildLowPointOctree(srcs);
            PointOctree3I highOctree = buildHighPointOctree(srcs, dstPos);

            //step 2: write vertices for all source tiles, and assign indices
            writeVertices(srcs, blockX, blockY, blockZ, level, lowOctree, highOctree, map, verts, output);

            //step 3: write indices to actually connect the vertices and build the mesh
            writeIndices(srcs[0], map, indices, lowOctree);*/

            writeVertices(dstPos, srcs[0], verts, buildLowPointOctrees(srcs), buildHighPointOctree(srcs, dstPos));
            writeIndices(srcs[0], indices);
        } finally {
            alloc.release(map);
        }
    }

    protected void writeVertices(@NonNull VoxelPos tilePos, @NonNull VoxelTile tile, @NonNull ByteBuf vertices, @NonNull PointOctree3I[] octrees, PointOctree3I highOctree) {
        SingleBiomeBlockAccess biomeAccess = new SingleBiomeBlockAccess();
        VoxelData data = new VoxelData();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();

        final int level = tilePos.level();
        final int blockX = tilePos.blockX();
        final int blockY = tilePos.blockY();
        final int blockZ = tilePos.blockZ();

        for (int i = 0, lim = tile.vertexCount(); i < lim; i++) {
            tile.getVertex(i, data);

            biomeAccess.biome(FastRegistry.getBiome(data.biome, Biomes.PLAINS));
            blockPos.setPos(blockX + (data.x << level >> POS_FRACT_SHIFT), blockY + (data.y << level >> POS_FRACT_SHIFT), blockZ + (data.z << level >> POS_FRACT_SHIFT));

            int vertexBase = VERTEX_FORMAT.appendVertex(vertices);

            IBlockState state = FastRegistry.getBlockState(data.state);
            ATTRIB_STATE.set(vertices, vertexBase, TexUVs.STATEID_TO_INDEXID.get(state));

            int blockLight = data.light & 0xF;
            int skyLight = data.light >> 4;
            ATTRIB_LIGHT.set(vertices, vertexBase, blockLight | (blockLight << 4), skyLight | (skyLight << 4));
            ATTRIB_COLOR.setRGB(vertices, vertexBase, mc.getBlockColors().colorMultiplier(state, biomeAccess, blockPos, 0));

            int x = data.x;
            int y = data.y;
            int z = data.z;

            if (data.highEdge != 0 && octrees[data.highEdge] != null) { //if this vertex actually extends into the next tile, we should round towards the nearest vertex in said neighboring tile (this is an imperfect solution)
                int pos = octrees[data.highEdge].nearestNeighbor(x, y, z);
                x = Int2_10_10_10_Rev.unpackX(pos);
                y = Int2_10_10_10_Rev.unpackY(pos);
                z = Int2_10_10_10_Rev.unpackZ(pos);
            }

            ATTRIB_POS_LOW.set(vertices, vertexBase, x, y, z);
            ATTRIB_POS_HIGH.setInt2_10_10_10_rev(vertices, vertexBase, highOctree != null ? highOctree.nearestNeighbor(x, y, z) : Int2_10_10_10_Rev.packXYZ(x, y, z));
        }
    }

    protected void writeIndices(@NonNull VoxelTile tile, @NonNull ByteBuf[] indices) {
        VoxelData data = new VoxelData();

        for (int i = 0, lim = tile.indexCount(); i < lim; i += 3) {
            //the mesh always consists of triangles. we can iterate through one triangle at a time and examine the provoking vertex
            // in order to determine which render layer it should be put on.

            int v0 = tile.getIndex(i + 0);
            int v1 = tile.getIndex(i + 1);
            int v2 = tile.getIndex(i + 2);

            tile.getVertex(v2, data);
            IBlockState state = FastRegistry.getBlockState(data.state);
            indices[renderType(state)].writeShortLE(v0).writeShortLE(v1).writeShortLE(v2);
        }
    }

    protected PointOctree3I[] buildLowPointOctrees(VoxelTile[] srcs) {
        PointOctree3I[] out = new PointOctree3I[8];

        final VoxelData data = new VoxelData();
        final IntList lowPoints = new IntArrayList();

        for (int i = 0, tx = 0; tx <= 1; tx++) {
            for (int ty = 0; ty <= 1; ty++) {
                for (int tz = 0; tz <= 1; tz++, i++) {
                    VoxelTile tile = srcs[i];
                    if (tile == null || i == 0) {
                        continue;
                    }

                    for (int j = 0, lim = tile.vertexCount(); j < lim; j++) {
                        tile.getVertex(j, data);

                        int px = (tx << (T_SHIFT + POS_FRACT_SHIFT)) + data.x;
                        int py = (ty << (T_SHIFT + POS_FRACT_SHIFT)) + data.y;
                        int pz = (tz << (T_SHIFT + POS_FRACT_SHIFT)) + data.z;

                        if (px >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && px <= Int2_10_10_10_Rev.MIN_XYZ_VALUE
                            && py >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && py <= Int2_10_10_10_Rev.MIN_XYZ_VALUE
                            && pz >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && pz <= Int2_10_10_10_Rev.MIN_XYZ_VALUE) { //this will only discard a very small minority of vertices
                            lowPoints.add(Int2_10_10_10_Rev.packXYZ(px, py, pz));
                        }
                    }

                    out[i] = lowPoints.isEmpty() ? null : new PointOctree3I(lowPoints.toIntArray());
                    lowPoints.clear();
                }
            }
        }

        return out;
    }

    protected PointOctree3I buildHighPointOctree(VoxelTile[] srcs, VoxelPos pos) {
        final VoxelData data = new VoxelData();
        final IntList highPoints = new IntArrayList();

        int offX = -(pos.x() & 1) << (T_SHIFT + POS_FRACT_SHIFT);
        int offY = -(pos.y() & 1) << (T_SHIFT + POS_FRACT_SHIFT);
        int offZ = -(pos.z() & 1) << (T_SHIFT + POS_FRACT_SHIFT);

        for (int i = 8, tx = BAKE_HIGH_RADIUS_MIN; tx <= BAKE_HIGH_RADIUS_MAX; tx++) {
            for (int ty = BAKE_HIGH_RADIUS_MIN; ty <= BAKE_HIGH_RADIUS_MAX; ty++) {
                for (int tz = BAKE_HIGH_RADIUS_MIN; tz <= BAKE_HIGH_RADIUS_MAX; tz++, i++) {
                    VoxelTile tile = srcs[i];
                    if (tile == null) {
                        continue;
                    }

                    for (int j = 0; j < tile.vertexCount(); j++) {
                        tile.getVertex(j, data);

                        int px = (tx << (T_SHIFT + POS_FRACT_SHIFT)) + (data.x << 1) + offX;
                        int py = (ty << (T_SHIFT + POS_FRACT_SHIFT)) + (data.y << 1) + offY;
                        int pz = (tz << (T_SHIFT + POS_FRACT_SHIFT)) + (data.z << 1) + offZ;

                        if (px >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && px <= Int2_10_10_10_Rev.MIN_XYZ_VALUE
                            && py >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && py <= Int2_10_10_10_Rev.MIN_XYZ_VALUE
                            && pz >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && pz <= Int2_10_10_10_Rev.MIN_XYZ_VALUE) { //this will only discard a very small minority of vertices
                            highPoints.add(Int2_10_10_10_Rev.packXYZ(px, py, pz));
                        }
                    }
                }
            }
        }

        return highPoints.isEmpty() ? null : new PointOctree3I(highPoints.toIntArray());
    }
}
