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

package net.daporkchop.fp2.util.datastructure;

import lombok.NonNull;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A collection of axis-aligned bounding boxes. Additional bounding boxes may be added at any time.
 *
 * @author DaPorkchop_
 */
//TODO: this needs to be optimized significantly
public final class WritableBVH<T extends AxisAlignedBB> implements Iterable<T> {
    protected final List<T> values = new ArrayList<>();

    /**
     * Adds the given bounding box to the BVH.
     *
     * @param value the bounding box to add
     */
    public void add(@NonNull T value) {
        this.values.add(value);
    }

    /**
     * Gets all the bounding boxes in the BVH that intersect the given point.
     *
     * @param point the point
     * @return all the bounding boxes in the BVH that intersect the given point
     */
    public List<T> intersecting(@NonNull Vec3d point) {
        List<T> out = new ArrayList<>();
        for (int i = 0, lim = this.values.size(); i < lim; i++) {
            if (this.values.get(i).intersects(point, point)) {
                out.add(this.values.get(i));
            }
        }
        return out;
    }

    @Override
    public Iterator<T> iterator() {
        return this.values.iterator();
    }
}
