/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.tchannel.api;

import com.google.common.util.concurrent.ListenableFuture;
import com.uber.tchannel.api.handlers.JSONRequestHandler;
import io.netty.buffer.Unpooled;
import io.netty.handler.logging.LogLevel;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.net.InetAddress;
import com.uber.tchannel.api.ResponseCode;
import com.uber.tchannel.api.handlers.RequestHandler;
import com.uber.tchannel.schemes.RawRequest;
import com.uber.tchannel.schemes.RawResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.uber.tchannel.api.handlers.RequestHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class PeerManagerTest {

    @Test
    public void testPeerAndConnections() throws Exception {

        InetAddress host = InetAddress.getByName("127.0.0.1");

        // create server
        final TChannel server = new TChannel.Builder("server")
                .register("echo", new EchoHandler())
                .setServerHost(host)
                .build();
        server.listen();

        int port = server.getListeningPort();

        // create client
        final TChannel client = new TChannel.Builder("json-server")
                .setServerHost(host)
                .build();
        client.listen();

        RawRequest req = new RawRequest(
                1000,
                "server",
                null,
                "echo",
                "title",
                "hello"
        );

        ListenableFuture<RawResponse> future = client.call(
                host,
                port,
                req
        );

        RawResponse res = future.get(100, TimeUnit.MILLISECONDS);
        assertEquals(res.getArg1().toString(CharsetUtil.UTF_8), "echo");
        assertEquals(res.getArg2().toString(CharsetUtil.UTF_8), "title");
        assertEquals(res.getArg3().toString(CharsetUtil.UTF_8), "hello");

        // checking the connections
        Map<String, Integer> stats = client.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 0);
        assertEquals((int)stats.get("connections.out"), 1);

        stats = server.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 1);
        assertEquals((int)stats.get("connections.out"), 0);

        client.shutdown();
        server.shutdown();

        stats = client.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 0);
        assertEquals((int)stats.get("connections.out"), 0);

        stats = server.getPeerManager().getStats();
        assertEquals((int)stats.get("connections.in"), 0);
        assertEquals((int)stats.get("connections.out"), 0);

    }

    protected  class EchoHandler implements RequestHandler {
        @Override
        public RawResponse handle(RawRequest request) {
            RawResponse response = new RawResponse(
                    request.getId(),
                    ResponseCode.OK,
                    request.getTransportHeaders(),
                    request.getArg1(),
                    request.getArg2(),
                    request.getArg3()
            );

            return response;
        }
    }
}


