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

package net.daporkchop.fp2.mode.common.server.task.piece;

import lombok.NonNull;
import net.daporkchop.fp2.mode.api.Compressed;
import net.daporkchop.fp2.mode.common.server.AbstractFarWorld;
import net.daporkchop.fp2.mode.common.server.TaskKey;
import net.daporkchop.fp2.mode.common.server.TaskStage;
import net.daporkchop.fp2.mode.api.piece.IFarPiece;
import net.daporkchop.fp2.mode.api.IFarPos;
import net.daporkchop.fp2.util.IReusablePersistent;
import net.daporkchop.fp2.util.threading.executor.LazyPriorityExecutor;
import net.daporkchop.fp2.util.threading.executor.LazyTask;

import java.util.List;
import java.util.stream.Stream;

/**
 * Handles exact updating for a piece.
 * <p>
 * Delegates the actual work to {@link ExactGeneratePieceTask} and {@link ExactScalePieceTask}.
 *
 * @author DaPorkchop_
 */
public class ExactUpdatePieceTask<POS extends IFarPos, P extends IFarPiece, D extends IReusablePersistent>
        extends AbstractPieceTask<POS, P, D, Compressed<POS, P>> {
    public ExactUpdatePieceTask(@NonNull AbstractFarWorld<POS, P, D> world, @NonNull TaskKey key, @NonNull POS pos, @NonNull TaskStage requestedBy) {
        super(world, key, pos, requestedBy);
    }

    @Override
    public Stream<? extends LazyTask<TaskKey, ?, Compressed<POS, P>>> before(@NonNull TaskKey key) throws Exception {
        //fully generate the piece before attempting an exact update
        return Stream.of(new GetPieceTask<>(this.world, key.withStage(TaskStage.LOAD), this.pos, TaskStage.EXACT));
    }

    @Override
    public Compressed<POS, P> run(@NonNull List<Compressed<POS, P>> params, @NonNull LazyPriorityExecutor<TaskKey> executor) throws Exception {
        if (this.pos.level() == 0) {
            //generate piece with exact generator
            this.world.blockAccess().prefetchAsync(
                    this.world.generatorExact().neededColumns(this.pos),
                    world -> this.world.generatorExact().neededCubes(world, this.pos))
                    .thenAccept(world -> executor.submit(new ExactGeneratePieceTask<>(this.world, this.key.withStage(TaskStage.EXACT_GENERATE), this.pos, world)));
        } else {
            //scale piece
            executor.submit(new ExactScalePieceTask<>(this.world, this.key.withStage(TaskStage.EXACT_SCALE), this.pos, TaskStage.EXACT));
        }
        return params.get(0);
    }
}
