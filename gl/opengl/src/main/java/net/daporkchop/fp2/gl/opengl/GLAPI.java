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

package net.daporkchop.fp2.gl.opengl;

import lombok.NonNull;
import net.daporkchop.fp2.gl.GLVersion;

import java.nio.ByteBuffer;

/**
 * Provides access to the OpenGL API.
 *
 * @author DaPorkchop_
 */
public interface GLAPI {
    //
    //
    // UTILITIES
    //
    //

    GLVersion version();

    //
    //
    // OpenGL 1.1
    //
    //

    int glGetError();

    int glGetInteger(int pname);

    String glGetString(int pname);

    //
    //
    // OpenGL 1.5
    //
    //

    int glGenBuffer();

    void glDeleteBuffer(int buffer);

    void glBindBuffer(int target, int buffer);

    void glBufferData(int target, long data_size, long data, int usage);

    void glBufferData(int target, @NonNull ByteBuffer data, int usage);

    void glBufferSubData(int target, long offset, long data_size, long data);

    void glBufferSubData(int target, long offset, @NonNull ByteBuffer data);

    void glGetBufferSubData(int target, long offset, long data_size, long data);

    void glGetBufferSubData(int target, long offset, @NonNull ByteBuffer data);

    long glMapBuffer(int target, int usage);

    void glUnmapBuffer(int target);

    //
    //
    // OpenGL 2.0
    //
    //

    int glCreateShader(int type);

    void glDeleteShader(int shader);

    void glShaderSource(int shader, @NonNull CharSequence... source);

    void glCompileShader(int shader);

    int glGetShaderi(int shader, int pname);

    String glGetShaderInfoLog(int shader);

    int glCreateProgram();

    void glDeleteProgram(int program);

    void glAttachShader(int program, int shader);

    void glDetachShader(int program, int shader);

    void glLinkProgram(int program);

    int glGetProgrami(int program, int pname);

    String glGetProgramInfoLog(int program);

    //
    //
    // OpenGL 3.0
    //
    //

    int glGetInteger(int pname, int idx);

    String glGetString(int pname, int idx);
}
