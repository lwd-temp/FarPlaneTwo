/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2020 DaPorkchop_
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

package net.daporkchop.fp2.client.gl;

import lombok.experimental.UtilityClass;
import net.daporkchop.fp2.util.DirectBufferReuse;
import net.daporkchop.lib.unsafe.PUnsafe;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

import static net.daporkchop.fp2.client.gl.OpenGL.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * @author DaPorkchop_
 */
@UtilityClass
public class MatrixHelper {
    private final long MATRIX = PUnsafe.allocateMemory(MAT4_SIZE);

    public int matrixIndex(int x, int y) {
        return x * 4 + y;
    }

    public long matrixOffset(int x, int y)  {
        return matrixIndex(x, y) << 2L;
    }

    public void infiniteZFar(float fovy, float aspect, float zNear) {
        //from http://dev.theomader.com/depth-precision/
        float radians = (float) Math.toRadians(fovy);
        float f = 1.0f / (float) Math.tan(radians * 0.5f);

        PUnsafe.setMemory(MATRIX, MAT4_SIZE, (byte) 0);

        PUnsafe.putFloat(MATRIX + matrixOffset(0, 0), f / aspect);
        PUnsafe.putFloat(MATRIX + matrixOffset(1, 1), f);
        PUnsafe.putFloat(MATRIX + matrixOffset(2, 2), -1.0f);
        PUnsafe.putFloat(MATRIX + matrixOffset(3, 2), -zNear);
        PUnsafe.putFloat(MATRIX + matrixOffset(2, 3), -1.0f);

        glMultMatrix(DirectBufferReuse.wrapFloat(MATRIX, MAT4_ELEMENTS));
    }

    public void reversedZ(float fovy, float aspect, float zNear) {
        //from http://dev.theomader.com/depth-precision/
        float radians = (float) Math.toRadians(fovy);
        float f = 1.0f / (float) Math.tan(radians * 0.5f);

        PUnsafe.setMemory(MATRIX, MAT4_SIZE, (byte) 0);

        PUnsafe.putFloat(MATRIX + matrixOffset(0, 0), f / aspect);
        PUnsafe.putFloat(MATRIX + matrixOffset(1, 1), f);
        PUnsafe.putFloat(MATRIX + matrixOffset(3, 2), zNear);
        PUnsafe.putFloat(MATRIX + matrixOffset(2, 3), -1.0f);

        glMultMatrix(DirectBufferReuse.wrapFloat(MATRIX, MAT4_ELEMENTS));
    }
}
