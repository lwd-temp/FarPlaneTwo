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

package net.daporkchop.fp2.mode.voxel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import net.daporkchop.fp2.client.gl.type.Int2_10_10_10_Rev;
import net.daporkchop.fp2.mode.api.IFarTile;
import net.daporkchop.lib.common.system.PlatformInfo;
import net.daporkchop.lib.unsafe.PUnsafe;

import static net.daporkchop.fp2.util.Constants.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * Stores server-side data for the voxel strategy.
 *
 * @author DaPorkchop_
 */
@Getter
public class VoxelTile implements IFarTile {
    //vertex layout (in ints):
    //0: pos
    //  ^ 2 bits are free
    //1: (lowEdge << 19) (highEdge << 16) | (biome << 8) | light
    //  ^ 10 bits are free
    //2: state

    protected static final int MAX_VERTEX_COUNT = (T_VERTS * T_VERTS * T_VERTS) * 64;
    protected static final int MAX_INDEX_COUNT = MAX_VERTEX_COUNT * 6;

    protected static final int VERTEX_SIZE = 3 * Integer.BYTES;
    protected static final int INDEX_SIZE = Character.BYTES;

    protected static final long VERTEX_START = 0L;
    protected static final long INDEX_START = VERTEX_START + MAX_VERTEX_COUNT * VERTEX_SIZE;

    protected static final long TILE_SIZE = INDEX_START + MAX_INDEX_COUNT * INDEX_SIZE;

    static void writeVertex(long base, VoxelData data) {
        PUnsafe.putInt(base + 0L, Int2_10_10_10_Rev.packXYZ(data.x, data.y, data.z));
        PUnsafe.putInt(base + 4L, ((data.lowEdge << 19) | (data.highEdge << 16)) | ((data.biome << 8) | data.light));
        PUnsafe.putInt(base + 8L, data.state);
    }

    static void readVertex(long base, VoxelData data) {
        int i0 = PUnsafe.getInt(base + 0L);
        int i1 = PUnsafe.getInt(base + 4L);
        int i2 = PUnsafe.getInt(base + 8L);

        data.x = Int2_10_10_10_Rev.unpackX(i0);
        data.y = Int2_10_10_10_Rev.unpackY(i0);
        data.z = Int2_10_10_10_Rev.unpackZ(i0);
        data.lowEdge = (i1 >> 19) & 7;
        data.highEdge = (i1 >> 16) & 7;

        data.biome = (i1 >> 8) & 0xFF;
        data.light = i1 & 0xFF;
        data.state = i2;
    }

    protected final long addr = PUnsafe.allocateMemory(this, TILE_SIZE);

    protected int vertexCount = -1; //the number of vertices in the tile that are set
    protected int indexCount = -1; //the number of indices in the tile that are set

    @Setter
    protected long extra = 0L;

    public VoxelTile() {
        this.reset();
    }

    /**
     * Gets the vertex at the given index.
     *
     * @param index  the index of the voxel to get
     * @param data the {@link VoxelData} instance to store the data into
     */
    public void getVertex(int index, @NonNull VoxelData data) {
        readVertex(this.addr + VERTEX_START + (long) checkIndex(this.vertexCount, index) * VERTEX_SIZE, data);
    }

    public void setVertex(int index, @NonNull VoxelData data) {
        writeVertex(this.addr + VERTEX_START + (long) checkIndex(this.vertexCount, index) * VERTEX_SIZE, data);
    }

    public int appendVertex(@NonNull VoxelData data) {
        int vertexIndex = this.vertexCount++;
        writeVertex(this.addr + VERTEX_START + (long) vertexIndex * VERTEX_SIZE, data);
        return vertexIndex;
    }

    public int triangleCount() {
        return this.indexCount / 3;
    }

    public int getIndex(int index) {
        return PUnsafe.getChar(this.addr + INDEX_START + (long) checkIndex(this.indexCount, index) * INDEX_SIZE);
    }

    public void getTriangle(int index, @NonNull int[] dst) {
        checkArg(dst.length >= 3, "destination array length (%d) must be at least 3!", dst.length);
        checkIndex(index >= 0 && index * 3 < this.indexCount, "total: 0-%d, index: %d*3=%d", this.indexCount, index, index * 3);

        long addr = this.addr + INDEX_START + (long) (index * 3) * INDEX_SIZE;
        dst[0] = PUnsafe.getChar(addr + 0L * INDEX_SIZE);
        dst[1] = PUnsafe.getChar(addr + 1L * INDEX_SIZE);
        dst[2] = PUnsafe.getChar(addr + 2L * INDEX_SIZE);
    }

    public VoxelTile appendIndex(int index) {
        PUnsafe.putChar(this.addr + INDEX_START + (long) this.indexCount++ * INDEX_SIZE, (char) index);
        return this;
    }

    public VoxelTile appendTriangle(int c0, int c1, int provoking) {
        long addr = this.addr + INDEX_START + (long) this.indexCount * INDEX_SIZE;
        this.indexCount += 3;

        PUnsafe.putChar(addr + 0L * INDEX_SIZE, (char) c0);
        PUnsafe.putChar(addr + 1L * INDEX_SIZE, (char) c1);
        PUnsafe.putChar(addr + 2L * INDEX_SIZE, (char) provoking);

        return this;
    }

    public VoxelTile appendQuad(int oppositeCorner, int c0, int c1, int provoking) {
        long addr = this.addr + INDEX_START + (long) this.indexCount * INDEX_SIZE;
        this.indexCount += 6;

        //first triangle
        PUnsafe.putChar(addr + 0L * INDEX_SIZE, (char) oppositeCorner);
        PUnsafe.putChar(addr + 1L * INDEX_SIZE, (char) c0);
        PUnsafe.putChar(addr + 2L * INDEX_SIZE, (char) provoking);

        //second triangle
        PUnsafe.putChar(addr + 3L * INDEX_SIZE, (char) c1);
        PUnsafe.putChar(addr + 4L * INDEX_SIZE, (char) oppositeCorner);
        PUnsafe.putChar(addr + 5L * INDEX_SIZE, (char) provoking);

        return this;
    }

    @Override
    public void reset() {
        this.extra = 0L;

        //setting both counts to 0 is enough to effectively delete all data in the tile
        this.vertexCount = 0;
        this.indexCount = 0;
    }

    @Override
    public void read(@NonNull ByteBuf src) {
        this.reset();

        //vertices
        this.vertexCount = src.readIntLE();
        if (PlatformInfo.IS_LITTLE_ENDIAN) { //little-endian: we can just copy the data
            src.readBytes(Unpooled.wrappedBuffer(this.addr + VERTEX_START, this.vertexCount * VERTEX_SIZE, false).writerIndex(0));
        } else { //read each int individually
            for (long addr = this.addr + VERTEX_START, end = addr + (long) this.vertexCount * VERTEX_SIZE; addr != end; addr += Integer.BYTES) {
                PUnsafe.putInt(addr, src.readIntLE());
            }
        }

        //indices
        this.indexCount = src.readIntLE();
        if (PlatformInfo.IS_LITTLE_ENDIAN) { //little-endian: we can just copy the data
            src.readBytes(Unpooled.wrappedBuffer(this.addr + INDEX_START, this.indexCount * INDEX_SIZE, false).writerIndex(0));
        } else { //read each int individually
            for (long addr = this.addr + INDEX_START, end = addr + (long) this.indexCount * INDEX_SIZE; addr != end; addr += Character.BYTES) {
                PUnsafe.putChar(addr, (char) src.readUnsignedShortLE());
            }
        }
    }

    @Override
    public boolean write(@NonNull ByteBuf dst) {
        if ((this.vertexCount | this.indexCount) == 0) { //tile is empty, nothing needs to be encoded
            return true;
        }

        checkState(this.indexCount % 3 == 0, "indexCount (%d) is not a multiple of 3!", this.indexCount);

        dst.ensureWritable(Integer.BYTES + this.vertexCount * VERTEX_SIZE + Integer.BYTES + this.indexCount * INDEX_SIZE);

        //vertices
        dst.writeIntLE(this.vertexCount);
        if (PlatformInfo.IS_LITTLE_ENDIAN) { //little-endian: we can just copy the data
            dst.writeBytes(Unpooled.wrappedBuffer(this.addr + VERTEX_START, this.vertexCount * VERTEX_SIZE, false));
        } else { //write each int individually
            for (long addr = this.addr + VERTEX_START, end = addr + (long) this.vertexCount * VERTEX_SIZE; addr != end; addr += Integer.BYTES) {
                dst.writeIntLE(PUnsafe.getInt(addr));
            }
        }

        //indices
        dst.writeIntLE(this.indexCount);
        if (PlatformInfo.IS_LITTLE_ENDIAN) { //little-endian: we can just copy the data
            dst.writeBytes(Unpooled.wrappedBuffer(this.addr + INDEX_START, this.indexCount * INDEX_SIZE, false));
        } else { //write each char individually
            for (long addr = this.addr + INDEX_START, end = addr + (long) this.indexCount * INDEX_SIZE; addr != end; addr += Character.BYTES) {
                dst.writeShortLE(PUnsafe.getChar(addr));
            }
        }

        return false;
    }
}
