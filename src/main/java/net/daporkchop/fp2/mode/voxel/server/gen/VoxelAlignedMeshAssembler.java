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

package net.daporkchop.fp2.mode.voxel.server.gen;

import lombok.NonNull;
import net.daporkchop.fp2.mode.voxel.VoxelData;
import net.daporkchop.fp2.mode.voxel.VoxelTile;

import java.util.Arrays;

import static net.daporkchop.fp2.mode.voxel.VoxelConstants.*;
import static net.daporkchop.fp2.util.Constants.*;
import static net.daporkchop.fp2.util.math.MathUtil.*;

/**
 * Helper class for assembling meshes consisting of precisely one vertex per voxel.
 *
 * @author DaPorkchop_
 */
public class VoxelAlignedMeshAssembler {
    protected static int vertexMapIndex(int dx, int dy, int dz, int i, int edge) {
        int j = CONNECTION_INDICES[i];
        int ddx = dx + ((j >> 2) & 1);
        int ddy = dy + ((j >> 1) & 1);
        int ddz = dz + (j & 1);

        return ((ddx * T_VERTS + ddy) * T_VERTS + ddz) * 3 + edge;
    }

    protected final int[] indices = new int[cb(T_VERTS) * EDGE_COUNT];
    protected final int[] edges = new int[cb(T_VERTS)];

    /**
     * Resets this instance in preparation for assembling a new tile.
     *
     * @return this instance
     */
    public VoxelAlignedMeshAssembler reset() {
        Arrays.fill(this.indices, -1);
        Arrays.fill(this.edges, 0);
        return this;
    }

    public void setEdgesAndVertices(@NonNull VoxelTile tile, int x, int y, int z, int edges, @NonNull VoxelData data, @NonNull int[] states) {
        //for some reason y is backwards, so we invert it as long as it isn't facing both directions
        if ((((edges >> 2) ^ (edges >> 3)) & 1) != 0) {
            edges ^= EDGE_DIR_MASK << 2;
        }

        int outIdx = (x * T_VERTS + y) * T_VERTS + z;
        this.edges[outIdx] = edges;

        data.lowEdge = (x == 0 ? 4 : 0) | (y == 0 ? 2 : 0) | (z == 0 ? 1 : 0);
        data.highEdge = ((x >> T_SHIFT) << 2) | ((y >> T_SHIFT) << 1) | (z >> T_SHIFT);

        int minIndexIdx = outIdx * EDGE_COUNT + 0;
        int maxIndexIdx = outIdx * EDGE_COUNT + 3;
        int anyEdgeStateIndex = -1;

        //emit one vertex for each edge with a corresponding set state
        for (int edge = 0; edge < EDGE_COUNT; edge++) {
            int state = states[edge];
            if (state >= 0) {
                data.state = state;
                anyEdgeStateIndex = this.indices[minIndexIdx + edge] = tile.appendVertex(data);
            }
        }

        //even if the current voxel has no renderable edges set, we need to be sure that at least one vertex is emitted in this voxel as it might still be referenced
        // by other neighboring voxels

        //ensure that we have a valid replacement vertex
        if (anyEdgeStateIndex < 0) {
            data.state = 0;
            anyEdgeStateIndex = tile.appendVertex(data);
        }

        //set all edge indices that lack their own vertex to the fallback one
        for (int idx = minIndexIdx; idx != maxIndexIdx; idx++) {
            if (this.indices[idx] < 0) {
                this.indices[idx] = anyEdgeStateIndex;
            }
        }
    }

    /**
     * Actually assembles the mesh indices, writing them to the given tile.
     *
     * @param tile the tile that indices should be written to
     */
    public void assemble(@NonNull VoxelTile tile) {
        for (int i = 0, dx = 0; dx < T_VOXELS; dx++, i += ((1 * T_VERTS + 0) * T_VERTS + 0) - ((0 * T_VERTS + T_VOXELS) * T_VERTS + 0)) {
            for (int dy = 0; dy < T_VOXELS; dy++, i += ((0 * T_VERTS + 1) * T_VERTS + 0) - ((0 * T_VERTS + 0) * T_VERTS + T_VOXELS)) {
                for (int dz = 0; dz < T_VOXELS; dz++, i++) {
                    int edges = this.edges[i];
                    if (edges == 0) { //no edges are set in this voxel, advance to the next one
                        continue;
                    }

                    for (int edge = 0; edge < EDGE_COUNT; edge++) {
                        if ((edges & (EDGE_DIR_MASK << (edge << 1))) == EDGE_DIR_NONE) {
                            continue;
                        }

                        int base = edge * CONNECTION_INDEX_COUNT;
                        int oppositeCorner, c0, c1, provoking;
                        if ((provoking = this.indices[vertexMapIndex(dx, dy, dz, base, edge)]) < 0
                            || (c0 = this.indices[vertexMapIndex(dx, dy, dz, base + 1, edge)]) < 0
                            || (c1 = this.indices[vertexMapIndex(dx, dy, dz, base + 2, edge)]) < 0
                            || (oppositeCorner = this.indices[vertexMapIndex(dx, dy, dz, base + 3, edge)]) < 0) {
                            continue; //skip if any of the vertices are missing
                        }

                        if ((edges & (EDGE_DIR_POSITIVE << (edge << 1))) != 0) { //the face has the positive bit set
                            tile.appendQuad(oppositeCorner, c0, c1, provoking);
                        }
                        if ((edges & (EDGE_DIR_NEGATIVE << (edge << 1))) != 0) { //the face has the negative bit set, output the face but with c0 and c1 swapped
                            tile.appendQuad(oppositeCorner, c1, c0, provoking);
                        }
                    }
                }
            }
        }

        tile.extra(0L); //TODO: compute neighbor connections
    }
}
