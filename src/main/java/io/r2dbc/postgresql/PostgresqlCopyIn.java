/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.postgresql;

import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.r2dbc.postgresql.client.Client;
import io.r2dbc.postgresql.message.backend.BackendMessage;
import io.r2dbc.postgresql.message.backend.CommandComplete;
import io.r2dbc.postgresql.message.backend.CopyInResponse;
import io.r2dbc.postgresql.message.backend.ReadyForQuery;
import io.r2dbc.postgresql.message.frontend.CopyData;
import io.r2dbc.postgresql.message.frontend.CopyDone;
import io.r2dbc.postgresql.message.frontend.CopyFail;
import io.r2dbc.postgresql.message.frontend.FrontendMessage;
import io.r2dbc.postgresql.message.frontend.Query;
import io.r2dbc.postgresql.util.Assert;
import io.r2dbc.postgresql.util.Operators;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

import static io.r2dbc.postgresql.PostgresqlResult.toResult;

/**
 * An implementation for {@link CopyData} PostgreSQL queries.
 */
final class PostgresqlCopyIn {

    private final ConnectionResources context;

    PostgresqlCopyIn(ConnectionResources context) {
        this.context = Assert.requireNonNull(context, "context must not be null");
    }

    public Mono<Integer> copy(String sql, Publisher<ByteBuffer> stdin) {
        Flux<FrontendMessage> insertData = Flux.from(stdin)
            .map(buffer -> new CopyData(Unpooled.wrappedBuffer(buffer)));

        return copyIn(sql, insertData);
    }

    private Mono<Integer> copyIn(String sql, Flux<FrontendMessage> frontendMessages) {
        Client client = context.getClient();

        Flux<BackendMessage> backendMessages = frontendMessages
            .doOnNext(client::send)
            .doOnError(e -> !(e instanceof IllegalArgumentException), (e) -> sendCopyFail(e.getMessage()))
            .doOnDiscard(ReferenceCounted.class, ReferenceCountUtil::release)
            .thenMany(client.exchange(Mono.just(CopyDone.INSTANCE)));

        return startCopy(sql)
            .concatWith(backendMessages)
            .doOnCancel(() -> sendCopyFail("Cancelled"))
            .as(Operators::discardOnCancel)
            .as(messages -> toResult(context, messages, ExceptionFactory.INSTANCE).getRowsUpdated());
    }

    private Flux<BackendMessage> startCopy(String sql) {
        return context.getClient().exchange(
                // ReadyForQuery is returned when an invalid query is provided
                backendMessage -> backendMessage instanceof CopyInResponse || backendMessage instanceof ReadyForQuery,
                Mono.just(new Query(sql))
            )
            .doOnNext(message -> {
                if (message instanceof CommandComplete) {
                    throw new IllegalArgumentException("Copy from stdin query expected, sql='" + sql + "', message=" + message);
                }
            });
    }

    private void sendCopyFail(String message) {
        context.getClient().exchange(Mono.just(new CopyFail("Copy operation failed: " + message)))
            .as(Operators::discardOnCancel)
            .subscribe();
    }

    @Override
    public String toString() {
        return "PostgresqlCopyIn{" +
            "context=" + this.context +
            '}';
    }

}
