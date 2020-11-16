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

package net.daporkchop.fp2.mode.common.server.task;

import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import lombok.Getter;
import lombok.NonNull;
import net.daporkchop.fp2.mode.api.Compressed;
import net.daporkchop.fp2.mode.api.IFarPos;
import net.daporkchop.fp2.mode.api.piece.IFarPiece;
import net.daporkchop.fp2.mode.common.server.AbstractFarWorld;
import net.daporkchop.fp2.mode.common.server.TaskKey;
import net.daporkchop.fp2.mode.common.server.TaskStage;
import net.daporkchop.fp2.util.IReusablePersistent;
import net.daporkchop.fp2.util.threading.executor.LazyTask;
import net.daporkchop.lib.concurrent.future.DefaultPFuture;

/**
 * @author DaPorkchop_
 */
@Getter
public abstract class AbstractFarTask<POS extends IFarPos, P extends IFarPiece, D extends IReusablePersistent, V extends IReusablePersistent, A>
        extends DefaultPFuture<Compressed<POS, V>> implements LazyTask<TaskKey, A, Compressed<POS, V>> {
    protected final AbstractFarWorld<POS, P, D> world;
    protected final TaskKey key;
    protected final POS pos;
    protected final TaskStage requestedBy;

    public AbstractFarTask(@NonNull AbstractFarWorld<POS, P, D> world, @NonNull TaskKey key, @NonNull POS pos, @NonNull TaskStage requestedBy) {
        super(ImmediateEventExecutor.INSTANCE);

        this.world = world;
        this.key = key;
        this.pos = pos;
        this.requestedBy = requestedBy;
    }

    @Override
    public AbstractFarTask<POS, P, D, V, A> setSuccess(@NonNull Compressed<POS, V> result) {
        super.setSuccess(result);
        return this;
    }

    @Override
    public AbstractFarTask<POS, P, D, V, A> setFailure(Throwable cause) {
        super.setFailure(cause);
        return this;
    }

    @Override
    public void cancel() {
        super.cancel(false);
    }

    public AbstractFarTask<POS, P, D, V, A> thenCopyStatusTo(@NonNull AbstractFarTask<POS, P, D, V, ?> dst) {
        this.addListener((GenericFutureListener<AbstractFarTask<POS, P, D, V, A>>) f -> {
            if (f.isSuccess()) {
                dst.setSuccess(f.getNow());
            } else if (f.isCancelled()) {
                dst.cancel();
            } else {
                dst.setFailure(f.cause());
            }
        });
        return this;
    }

    @Override
    public String toString() {
        return this.pos.toString();
    }
}
