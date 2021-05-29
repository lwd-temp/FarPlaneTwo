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

package net.daporkchop.fp2.mode.voxel.server.scale;

import lombok.NonNull;
import net.daporkchop.fp2.mode.api.server.gen.IFarScaler;
import net.daporkchop.fp2.mode.voxel.VoxelData;
import net.daporkchop.fp2.mode.voxel.VoxelPos;
import net.daporkchop.fp2.mode.voxel.VoxelTile;
import net.daporkchop.fp2.util.datastructure.WritableBVH;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.lang.Math.*;
import static net.daporkchop.fp2.mode.voxel.VoxelConstants.*;
import static net.daporkchop.fp2.util.Constants.*;
import static net.daporkchop.fp2.util.math.MathUtil.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 * @see <a href="https://www.researchgate.net/publication/220792145_Model_Simplification_Using_Vertex-Clustering">this paper</a>
 */
public class VoxelScalerVertexClustering implements IFarScaler<VoxelPos, VoxelTile> {
    //protected static final int SRC_MIN = -4;
    //protected static final int SRC_MAX = (T_VOXELS << 1) + 4;
    protected static final int SRC_MIN = 0;
    protected static final int SRC_MAX = (T_VOXELS + 2) << 1;
    protected static final int SRC_SIZE = SRC_MAX - SRC_MIN;

    protected static final int SRC_TILE_MIN = SRC_MIN >> T_SHIFT;
    protected static final int SRC_TILE_MAX = ((SRC_MAX - 1) >> T_SHIFT) + 1;
    protected static final int SRC_TILE_SIZE = SRC_TILE_MAX - SRC_TILE_MIN;

    protected static final int DST_MIN = -1;
    protected static final int DST_MAX = T_VOXELS + 1;
    protected static final int DST_SIZE = DST_MAX - DST_MIN;

    protected static boolean outOfBounds(VoxelData data) {
        final int min = SRC_MIN << POS_FRACT_SHIFT >> 1;
        final int max = SRC_MAX << POS_FRACT_SHIFT >> 1;
        return data.x < min || data.x > max || data.y < min || data.y > max || data.z < min || data.z > max;
    }

    @Override
    public Stream<VoxelPos> outputs(@NonNull VoxelPos srcPos) {
        return Stream.of(srcPos.up()); //TODO: fix this
    }

    @Override
    public Stream<VoxelPos> inputs(@NonNull VoxelPos dstPos) {
        checkArg(dstPos.level() > 0, "cannot generate inputs for level 0!");

        int x = dstPos.x() << 1;
        int y = dstPos.y() << 1;
        int z = dstPos.z() << 1;
        int level = dstPos.level() - 1;

        VoxelPos[] positions = new VoxelPos[cb(SRC_TILE_SIZE)];
        for (int i = 0, dx = SRC_TILE_MIN; dx < SRC_TILE_MAX; dx++) {
            for (int dy = SRC_TILE_MIN; dy < SRC_TILE_MAX; dy++) {
                for (int dz = SRC_TILE_MIN; dz < SRC_TILE_MAX; dz++) {
                    positions[i++] = new VoxelPos(level, x + dx, y + dy, z + dz);
                }
            }
        }
        return Arrays.stream(positions);
    }

    @Override
    public long scale(@NonNull VoxelTile[] srcTiles, @NonNull VoxelTile dst) {
        //read all the triangles from all source tiles, compute their weights and add them to the list
        List<ExtraVoxelData> srcVerts = new ArrayList<>();
        for (int ti = 0, tx = SRC_TILE_MIN; tx < SRC_TILE_MAX; tx++) {
            for (int ty = SRC_TILE_MIN; ty < SRC_TILE_MAX; ty++) {
                for (int tz = SRC_TILE_MIN; tz < SRC_TILE_MAX; tz++, ti++) {
                    VoxelTile tile = srcTiles[ti];
                    if (tile == null) {
                        continue;
                    }

                    for (int i = 0, len = tile.indexCount(); i < len; i += 3) {
                        ExtraVoxelData data0 = new ExtraVoxelData();
                        ExtraVoxelData data1 = new ExtraVoxelData();
                        ExtraVoxelData data2 = new ExtraVoxelData();

                        //load vertices
                        tile.getVertex(tile.getIndex(i + 0), data0);
                        tile.getVertex(tile.getIndex(i + 1), data1);
                        tile.getVertex(tile.getIndex(i + 2), data2);

                        data0.x = data0.x + (tx << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data0.y = data0.y + (ty << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data0.z = data0.z + (tz << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data1.x = data1.x + (tx << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data1.y = data1.y + (ty << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data1.z = data1.z + (tz << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data2.x = data2.x + (tx << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data2.y = data2.y + (ty << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;
                        data2.z = data2.z + (tz << (POS_FRACT_SHIFT + T_SHIFT)) >> 1;

                        int mask = (tx == SRC_TILE_MAX - 1 ? 4 : 0) | (ty == SRC_TILE_MAX - 1 ? 2 : 0) | (tz == SRC_TILE_MAX - 1 ? 1 : 0);
                        data0.lowEdge &= ~mask;
                        data1.lowEdge &= ~mask;
                        data2.lowEdge &= ~mask;
                        data0.highEdge &= mask;
                        data1.highEdge &= mask;
                        data2.highEdge &= mask;

                        /*if ((tx & 3) == tx && (ty & 3) == ty && (tz & 3) == tz) {
                            int mask = ((tx >> 1) << 2) | ((ty >> 1) << 1) | (tz >> 1);
                            data0.lowEdge &= ~mask;
                            data1.lowEdge &= ~mask;
                            data2.lowEdge &= ~mask;
                            data0.highEdge &= mask;
                            data1.highEdge &= mask;
                            data2.highEdge &= mask;
                        } else {
                            data0.lowEdge = data1.lowEdge = data2.lowEdge = 0;
                            data0.highEdge = data1.highEdge = data2.highEdge = 7;
                        }*/

                        if (outOfBounds(data0) && outOfBounds(data1) && outOfBounds(data2)) {
                            continue;
                        }

                        data0.calcWeight(data1, data2);
                        data1.calcWeight(data0, data2);
                        data2.calcWeight(data0, data1);

                        srcVerts.add(data0);
                        srcVerts.add(data1);
                        srcVerts.add(data2);
                    }
                }
            }
        }

        //sort voxels by weight
        ExtraVoxelData[] srcVertsSorted = srcVerts.toArray(new ExtraVoxelData[0]);
        Arrays.sort(srcVertsSorted, Comparator.comparingDouble(d -> -d.weight));

        //build BVH of intersected tiles
        WritableBVH<BoundedVoxelData> bvh = new WritableBVH<>();
        for (ExtraVoxelData data : srcVertsSorted) {
            List<BoundedVoxelData> datas = bvh.intersecting(new Vec3d(data.x, data.y, data.z));
            if (datas.isEmpty()) {
                bvh.add(new BoundedVoxelData(data));
            }
        }

        //build mesh (this is not only slow, but also generates a hilariously bad mesh)
        for (ExtraVoxelData data : srcVerts) {
            List<BoundedVoxelData> datas = bvh.intersecting(new Vec3d(data.x, data.y, data.z));
            checkState(!datas.isEmpty());

            VoxelData position = datas.get(0).data;
            data.x = position.x;
            data.y = position.y;
            data.z = position.z;

            dst.appendIndex(dst.appendVertex(data));
        }

        return 0L;
    }

    protected static class ExtraVoxelData extends VoxelData {
        public double weight;
        
        public void calcWeight(VoxelData d1, VoxelData d2) {
            double x1 = this.x - d1.x;
            double y1 = this.y - d1.y;
            double z1 = this.z - d1.z;
            double x2 = this.x - d2.x;
            double y2 = this.y - d2.y;
            double z2 = this.z - d2.z;
            this.weight = cos(acos((x1 * x2 + y1 * y2 + z1 * z1) * fastInvSqrt((x1 * x1 + y1 * y1 + z1 * z1) * (x2 * x2 + y2 * y2 + z2 * z2))) * 0.5d);
        }
    }

    protected static class BoundedVoxelData extends AxisAlignedBB {
        protected final VoxelData data = new VoxelData();

        public BoundedVoxelData(ExtraVoxelData data) {
            super(data.x - (1 << POS_FRACT_SHIFT) + 1, data.y - (1 << POS_FRACT_SHIFT) + 1, data.z - (1 << POS_FRACT_SHIFT) + 1,
                    data.x + (1 << POS_FRACT_SHIFT) - 1, data.y + (1 << POS_FRACT_SHIFT) - 1, data.z + (1 << POS_FRACT_SHIFT) - 1);

            this.data.x = data.x;
            this.data.y = data.y;
            this.data.z = data.z;
            this.data.state = data.state;
            this.data.light = data.light;
            this.data.biome = data.biome;
        }
    }
}
