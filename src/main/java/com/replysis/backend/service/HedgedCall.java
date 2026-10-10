package com.replysis.backend.service;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Asks a second source when the first is slow, and uses whichever answers well first.
 *
 * An answer is only as quick as its slowest call. Most calls to the main model come back in a quarter of a second, but
 * now and then one sits for a second or more (the provider is busy), and the candidate watches an empty screen for it.
 * The backup model is already there for failures. This lets it help with slowness too: if the main call has not
 * answered after {@code hedgeAfterMs}, the backup is asked as well, and the first good reply wins. The loser is
 * cancelled, or discarded if it arrives anyway.
 *
 * It does not change what happens on a failure: a main call that comes back quickly, good or not, is returned as it
 * is, so the existing retry and fallback code still runs exactly as before. Only a main call that is still silent
 * after the delay is raced.
 */
public final class HedgedCall {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "hedged-call");
        t.setDaemon(true);
        return t;
    });

    private HedgedCall() {}

    /** The shared pool, for callers that do not bring their own. */
    public static ExecutorService pool() { return POOL; }

    /**
     * @param primary      the usual call
     * @param backup       the call to add when the usual one is slow
     * @param hedgeAfterMs how long the usual call gets alone; zero or less disables hedging
     * @param good         whether a reply is one worth using (a 200, not an error)
     * @param discard      releases a reply that lost the race (closes its stream)
     */
    public static <T> T race(Callable<T> primary, Callable<T> backup, long hedgeAfterMs,
                             Predicate<T> good, Consumer<T> discard, ExecutorService pool) throws Exception {
        if (hedgeAfterMs <= 0 || backup == null) return primary.call();

        CompletionService<T> done = new ExecutorCompletionService<>(pool);
        Future<T> first = done.submit(primary);

        Future<T> early = done.poll(hedgeAfterMs, TimeUnit.MILLISECONDS);
        if (early != null) return unwrap(early);        // quick: good or not, the caller deals with it as before

        Future<T> second = done.submit(backup);
        T primaryResult = null;
        Exception primaryError = null;

        for (int i = 0; i < 2; i++) {
            Future<T> f = done.take();
            T value = null;
            Exception error = null;
            try {
                value = f.get();
            } catch (ExecutionException e) {
                error = e.getCause() instanceof Exception ex ? ex : e;
            }
            boolean isPrimary = f == first;
            if (value != null && good.test(value)) {
                Future<T> other = isPrimary ? second : first;
                finish(other, discard);
                return value;
            }
            if (isPrimary) { primaryResult = value; primaryError = error; }
            else if (value != null && primaryResult == null && primaryError == null) {
                // the backup answered badly and the main call is still out: keep waiting for it
                discardLater(value, discard);
            } else if (value != null) {
                discardLater(value, discard);
            }
        }
        if (primaryResult != null) return primaryResult;
        if (primaryError != null) throw primaryError;
        throw new IllegalStateException("neither call produced a reply");
    }

    private static <T> T unwrap(Future<T> f) throws Exception {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            throw e;
        }
    }

    /** Stops the loser: cancels it if it is still running, releases its reply if it had already arrived. */
    private static <T> void finish(Future<T> loser, Consumer<T> discard) {
        if (!loser.cancel(true) && loser.isDone() && !loser.isCancelled()) {
            try {
                T value = loser.get();
                if (value != null) discard.accept(value);
            } catch (Exception ignored) {
                // nothing to release
            }
        }
    }

    private static <T> void discardLater(T value, Consumer<T> discard) {
        try { discard.accept(value); } catch (Exception ignored) { /* already closed */ }
    }
}
