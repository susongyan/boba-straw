package io.github.susongyan.bobastraw;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Internal CompletionStage helpers that preserve cancellation back to a Redis command. */
final class BobaStrawStages {
    private BobaStrawStages() {
    }

    static <T, R> CompletionStage<R> map(
        final CompletionStage<T> source,
        final Function<? super T, ? extends R> mapper
    ) {
        final CompletableFuture<R> result = new CompletableFuture<R>();
        source.whenComplete((value, error) -> {
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            try {
                result.complete(mapper.apply(value));
            } catch (Throwable mappingError) {
                result.completeExceptionally(mappingError);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                source.toCompletableFuture().cancel(false);
            }
        });
        return result;
    }
}
