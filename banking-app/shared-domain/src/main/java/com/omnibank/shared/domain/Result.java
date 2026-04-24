package com.omnibank.shared.domain;

import java.util.Objects;
import java.util.function.Function;

/**
 * Lightweight result type for operations that can fail with a domain error.
 * Used in validation paths where exceptions would be overkill. Sealed so
 * pattern-match over it is exhaustive.
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok<T, E>;
    }

    default boolean isErr() {
        return this instanceof Err<T, E>;
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> fn) {
        return switch (this) {
            case Ok<T, E> ok -> ok(fn.apply(ok.value));
            case Err<T, E> err -> err(err.error);
        };
    }

    default T orThrow(Function<? super E, ? extends RuntimeException> toEx) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value;
            case Err<T, E> err -> { throw toEx.apply(err.error); }
        };
    }

    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok { Objects.requireNonNull(value, "value"); }
    }

    record Err<T, E>(E error) implements Result<T, E> {
        public Err { Objects.requireNonNull(error, "error"); }
    }
}
