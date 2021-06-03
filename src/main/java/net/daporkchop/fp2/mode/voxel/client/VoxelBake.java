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
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
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
import net.daporkchop.fp2.util.SingleBiomeBlockAccess;
import net.daporkchop.fp2.util.datastructure.PointOctree3I;
import net.daporkchop.lib.common.util.PArrays;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.lang.Math.*;
import static net.daporkchop.fp2.client.ClientConstants.*;
import static net.daporkchop.fp2.client.gl.GLCompatibilityHelper.*;
import static net.daporkchop.fp2.client.gl.OpenGL.*;
import static net.daporkchop.fp2.mode.voxel.VoxelConstants.*;
import static net.daporkchop.fp2.util.BlockType.*;
import static net.daporkchop.fp2.util.Constants.*;

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

    protected static final IVertexAttribute.Int4 ATTRIB_POS_LOW = IVertexAttribute.Int4.builder(ATTRIB_COLOR)
            .alignAndPadTo(EFFECTIVE_VERTEX_ATTRIBUTE_ALIGNMENT)
            .type(WORKAROUND_AMD_INT_2_10_10_10_REV ? VertexAttributeType.SHORT : VertexAttributeType.INT_2_10_10_10_REV)
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

        /*//step 1: build octrees
        PointOctree3I lowOctree = buildLowPointOctree(srcs);
        PointOctree3I highOctree = buildHighPointOctree(srcs, dstPos);

        //step 2: write vertices for all source tiles, and assign indices
        writeVertices(srcs, blockX, blockY, blockZ, level, lowOctree, highOctree, map, verts, output);

        //step 3: write indices to actually connect the vertices and build the mesh
        writeIndices(srcs[0], map, indices, lowOctree);*/

        writeVertices(dstPos, srcs[0], srcs, verts, buildLowPointOctrees(srcs), buildHighPointOctree(srcs, dstPos));
        writeIndices(srcs[0], indices);

        class Baker {
            IntSet positionsThatExist = new IntOpenHashSet();
            final BitSet[] selfEdgeTileIndices = PArrays.filled(8, BitSet[]::new, i -> new BitSet());
            final BitSet[] neighborEdgeTileIndices = PArrays.filled(8, BitSet[]::new, i -> new BitSet());

            void computeSelfEdgeTileIndices() {
                VoxelData data = new VoxelData();
                VoxelTile tile = srcs[0];

                for (int i = 0, lim = tile.vertexCount(); i < lim; i++) {
                    tile.getVertex(i, data);
                    if (data.highEdge != 0) {
                        this.selfEdgeTileIndices[data.highEdge].set(i);
                    }

                    this.positionsThatExist.add(Int2_10_10_10_Rev.packXYZ(data.x, data.y, data.z));
                }
            }

            void computeNeighborEdgeTileIndices() {
                VoxelData data = new VoxelData();

                for (int ti = 1; ti < 8; ti++) {
                    VoxelTile tile = srcs[ti];
                    if (tile == null) {
                        continue;
                    }

                    BitSet bs = this.neighborEdgeTileIndices[ti];
                    for (int i = 0, lim = tile.vertexCount(); i < lim; i++) {
                        tile.getVertex(i, data);
                        if ((data.lowEdge & ti) == ti) {
                            data.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);
                            if (!this.positionsThatExist.contains(Int2_10_10_10_Rev.packXYZ(data.x, data.y, data.z))) {
                                bs.set(i);
                            }
                        }
                    }
                }
            }

            int[][] buildEdgeVertexConnectionGraph(@NonNull VoxelTile tile, @NonNull BitSet allowedIndices) {
                IntList[] lists = PArrays.filled(tile.vertexCount(), IntList[]::new, i -> new IntArrayList());

                for (int i = 0, lim = tile.indexCount(); i < lim; i += 3) {
                    int v0 = tile.getIndex(i + 0);
                    int v1 = tile.getIndex(i + 1);
                    int v2 = tile.getIndex(i + 2);

                    if (allowedIndices.get(v0) && allowedIndices.get(v1)) {
                        lists[v0].add(v1);
                        lists[v1].add(v0);
                    }
                    if (allowedIndices.get(v0) && allowedIndices.get(v2)) {
                        lists[v0].add(v2);
                        lists[v2].add(v0);
                    }
                    if (allowedIndices.get(v1) && allowedIndices.get(v2)) {
                        lists[v1].add(v2);
                        lists[v2].add(v1);
                    }
                }

                return Stream.of(lists).map(IntList::toIntArray)
                        .map(arr -> arr.length <= 2 ? arr : Arrays.copyOf(arr, 2)) //TODO: remove this and handle arbitrary connections properly
                        .toArray(int[][]::new);
            }

            List<int[]> buildConnectionStrips(@NonNull int[][] graph) {
                BitSet used = new BitSet(graph.length);
                List<int[]> out = new ArrayList<>();
                IntList buf = new IntArrayList();

                for (int start = 0; start < graph.length; start++) {
                    if (used.get(start) || graph[start].length != 1) {
                        continue;
                    }
                    used.set(start);

                    buf.add(start);
                    for (int curr = graph[start][0]; ; ) {
                        used.set(curr);
                        buf.add(curr);
                        if (graph[curr].length == 1) {
                            break;
                        }

                        if (used.get(graph[curr][0])) {
                            if (used.get(graph[curr][1])) {
                                break;
                            }
                            curr = graph[curr][1];
                        } else {
                            curr = graph[curr][0];
                        }
                    }

                    out.add(buf.toIntArray());
                    buf.clear();
                }

                return out;
            }

            void stitchEdges() {
                VoxelData d0 = new VoxelData(), d1 = new VoxelData(), d2 = new VoxelData(), d3 = new VoxelData();

                /*for (int i = 0; i < srcs[0].vertexCount(); i++) {
                    srcs[0].getVertex(i, d0);
                    d0.state = 0;
                    int i0 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z)) / VERTEX_FORMAT.size();
                    int i1 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z - POS_ONE)) / VERTEX_FORMAT.size();
                    int i2 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y + POS_ONE, d0.z)) / VERTEX_FORMAT.size();
                    indices[0].writeShortLE(i0).writeShortLE(i1).writeShortLE(i2);
                }*/

                for (int ti = 1; ti < 8; ti++) {
                    VoxelTile t0 = srcs[0];
                    VoxelTile t1 = srcs[ti];
                    if (srcs[ti] == null) {
                        continue;
                    }

                    this.neighborEdgeTileIndices[ti].clear();
                    this.computeNeighborEdgeTileIndices();
                    int[][] graph0 = this.buildEdgeVertexConnectionGraph(srcs[0], this.selfEdgeTileIndices[ti]);
                    int[][] graph1 = this.buildEdgeVertexConnectionGraph(srcs[ti], this.neighborEdgeTileIndices[ti]);
                    List<int[]> strips0 = this.buildConnectionStrips(graph0);
                    List<int[]> strips1 = this.buildConnectionStrips(graph1);

                    IntSet positionsThatExist = this.positionsThatExist;
                    this.positionsThatExist = new IntOpenHashSet();
                    this.neighborEdgeTileIndices[ti].clear();
                    this.computeNeighborEdgeTileIndices();
                    int[][] graph1_1 = this.buildEdgeVertexConnectionGraph(srcs[ti], this.neighborEdgeTileIndices[ti]);
                    this.positionsThatExist = positionsThatExist;

                    for (int v0 = 0; v0 < t1.vertexCount(); v0++) {
                        t1.getVertex(v0, d0);
                        d0.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);
                        if (this.positionsThatExist.contains(Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z))) {
                            continue;
                        }
                        /*d0.state = 0;
                        int i0 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z)) / VERTEX_FORMAT.size();
                        int i1 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z + POS_ONE)) / VERTEX_FORMAT.size();
                        int i2 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y + POS_ONE, d0.z)) / VERTEX_FORMAT.size();
                        indices[0].writeShortLE(i0).writeShortLE(i1).writeShortLE(i2);*/

                        if (graph1_1[v0].length != 2) {
                            continue;
                        }

                        t1.getVertex(graph1_1[v0][0], d1);
                        t1.getVertex(graph1_1[v0][1], d2);
                        d1.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);
                        d2.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);

                        int i0 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z)) / VERTEX_FORMAT.size();
                        int i1 = writeVertex(verts, d1, -1, Int2_10_10_10_Rev.packXYZ(d1.x, d1.y, d1.z)) / VERTEX_FORMAT.size();
                        int i2 = writeVertex(verts, d2, -1, Int2_10_10_10_Rev.packXYZ(d2.x, d2.y, d2.z)) / VERTEX_FORMAT.size();
                        indices[0].writeShortLE(i0).writeShortLE(i1).writeShortLE(i2);
                    }

                    /*for (int[] strip0 : strips0) {
                        for (int v0 : strip0) {
                            t0.getVertex(v0, d0);
                            d0.state = 0;
                            int i0 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z)) / VERTEX_FORMAT.size();
                            int i1 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z - POS_ONE)) / VERTEX_FORMAT.size();
                            int i2 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y + POS_ONE, d0.z)) / VERTEX_FORMAT.size();
                            indices[0].writeShortLE(i0).writeShortLE(i1).writeShortLE(i2);
                        }
                    }*/

                    /*for (int[] strip1 : strips1) {
                        for (int v0 : strip1) {
                            t1.getVertex(v0, d0);
                            d0.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);
                            d0.state = 0;
                            int i0 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z)) / VERTEX_FORMAT.size();
                            int i1 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z + POS_ONE)) / VERTEX_FORMAT.size();
                            int i2 = writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y + POS_ONE, d0.z)) / VERTEX_FORMAT.size();
                            indices[0].writeShortLE(i0).writeShortLE(i1).writeShortLE(i2);
                        }
                    }*/

                    /*for (int[] strip0 : strips0) {
                        t0.getVertex(strip0[0], d0);
                        t0.getVertex(strip0[strip0.length - 1], d1);

                        final int ti_final = ti;
                        final VoxelTile t1_final = t1;
                        int[] strip1 = strips1.stream().min(Comparator.comparingInt(strip -> {
                            t1_final.getVertex(strip[0], d2);
                            t1_final.getVertex(strip[strip.length - 1], d3);
                            d2.offsetMask(ti_final, T_VOXELS << POS_FRACT_SHIFT);
                            d3.offsetMask(ti_final, T_VOXELS << POS_FRACT_SHIFT);
                            return (min(d0.distanceSq(d2), d0.distanceSq(d3)) + min(d1.distanceSq(d2), d1.distanceSq(d3)));
                        })).get();

                        t1.getVertex(strip1[0], d2);
                        t1.getVertex(strip1[strip1.length - 1], d3);
                        d2.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);
                        d3.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);

                        final VoxelTile t0_final = t0;
                        if (strips0.stream().filter(Objects::nonNull).min(Comparator.comparingInt(strip -> {
                            t0_final.getVertex(strip[0], d0);
                            t0_final.getVertex(strip[strip.length - 1], d1);
                            return (min(d0.distanceSq(d2), d0.distanceSq(d3)) + min(d1.distanceSq(d2), d1.distanceSq(d3)));
                        })).get() != strip0) {
                            continue;
                        }

                        //strips0.set(strips0.indexOf(strip0), null);
                        //strips1.remove(strip1);

                        t0.getVertex(strip0[0], d0);
                        t0.getVertex(strip0[strip0.length - 1], d1);

                        if (d0.distanceSq(d2) > d1.distanceSq(d3)) {
                            for (int i = 0, j = strip1.length - 1; i < strip1.length >> 1; i++, j--) {
                                int t = strip1[i];
                                strip1[i] = strip1[j];
                                strip1[j] = t;
                            }
                        }

                        for (int i = 0, j = 0; i < strip0.length && j < strip1.length && (i + 1 < strip0.length || j + 1 < strip1.length); i++, j++) {
                            t0.getVertex(strip0[i], d0);
                            if (i + 1 < strip0.length) {
                                t0.getVertex(strip0[i + 1], d1);
                            }
                            t1.getVertex(strip1[j], d2);
                            if (j + 1 < strip1.length) {
                                t1.getVertex(strip1[j + 1], d3);
                            }

                            d2.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);
                            d3.offsetMask(ti, T_VOXELS << POS_FRACT_SHIFT);

                            int i1 = writeVertex(verts, d1, -1, Int2_10_10_10_Rev.packXYZ(d1.x, d1.y, d1.z)) / VERTEX_FORMAT.size();
                            int i2 = writeVertex(verts, d2, -1, Int2_10_10_10_Rev.packXYZ(d2.x, d2.y, d2.z)) / VERTEX_FORMAT.size();

                            if (i + 1 < strip0.length) {
                                indices[0].writeShortLE(writeVertex(verts, d0, -1, Int2_10_10_10_Rev.packXYZ(d0.x, d0.y, d0.z)) / VERTEX_FORMAT.size()).writeShortLE(i1).writeShortLE(i2);
                            }
                            if (j + 1 < strip1.length) {
                                indices[0].writeShortLE(writeVertex(verts, d3, -1, Int2_10_10_10_Rev.packXYZ(d3.x, d3.y, d3.z)) / VERTEX_FORMAT.size()).writeShortLE(i1).writeShortLE(i2);
                            }
                        }
                    }*/
                }
            }

            void run() {
                this.computeSelfEdgeTileIndices();
                this.computeNeighborEdgeTileIndices();
                this.stitchEdges();
            }
        }

        new Baker().run();
    }

    protected void writeVertices(@NonNull VoxelPos tilePos, @NonNull VoxelTile tile, @NonNull VoxelTile[] srcs, @NonNull ByteBuf vertices, @NonNull PointOctree3I[] octrees, PointOctree3I highOctree) {
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

            int lowPos = Int2_10_10_10_Rev.packXYZ(data.x, data.y, data.z);
            if (data.highEdge != 0 && octrees[data.highEdge] != null) { //if this vertex actually extends into the next tile, we should round towards the nearest vertex in said neighboring tile (this is an imperfect solution)
                lowPos = octrees[data.highEdge].nearestNeighbor(data.x, data.y, data.z);
                data.x = Int2_10_10_10_Rev.unpackX(lowPos);
                data.y = Int2_10_10_10_Rev.unpackY(lowPos);
                data.z = Int2_10_10_10_Rev.unpackZ(lowPos);

                tile.setVertex(i, data);
            }

            ATTRIB_POS_LOW.setInt2_10_10_10_rev(vertices, vertexBase, lowPos);
            ATTRIB_POS_HIGH.setInt2_10_10_10_rev(vertices, vertexBase, highOctree != null ? highOctree.nearestNeighbor(data.x, data.y, data.z) : lowPos);
        }
    }

    protected int writeVertex(@NonNull ByteBuf vertices, @NonNull VoxelData data, int color, int highPos) {
        int vertexBase = VERTEX_FORMAT.appendVertex(vertices);

        IBlockState state = FastRegistry.getBlockState(data.state);
        ATTRIB_STATE.set(vertices, vertexBase, TexUVs.STATEID_TO_INDEXID.get(state));

        int blockLight = data.light & 0xF;
        int skyLight = data.light >> 4;
        ATTRIB_LIGHT.set(vertices, vertexBase, blockLight | (blockLight << 4), skyLight | (skyLight << 4));
        ATTRIB_COLOR.setRGB(vertices, vertexBase, color);

        ATTRIB_POS_LOW.set(vertices, vertexBase, data.x, data.y, data.z, 0);
        ATTRIB_POS_LOW.setInt2_10_10_10_rev(vertices, vertexBase, highPos);
        ATTRIB_POS_HIGH.setInt2_10_10_10_rev(vertices, vertexBase, highPos);

        return vertexBase;
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

                        if (px >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && px <= Int2_10_10_10_Rev.MAX_XYZ_VALUE
                            && py >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && py <= Int2_10_10_10_Rev.MAX_XYZ_VALUE
                            && pz >= Int2_10_10_10_Rev.MIN_XYZ_VALUE && pz <= Int2_10_10_10_Rev.MAX_XYZ_VALUE) { //this will only discard a very small minority of vertices
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
