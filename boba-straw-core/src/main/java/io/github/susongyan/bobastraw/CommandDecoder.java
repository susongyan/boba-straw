package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.util.function.Function;

/** A typed reply projection; it never schedules, retries or changes connection ownership. */
@FunctionalInterface
interface CommandDecoder<T> extends Function<RespValue, T> {
}
