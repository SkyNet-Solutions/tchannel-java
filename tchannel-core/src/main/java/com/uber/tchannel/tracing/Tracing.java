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

package com.uber.tchannel.tracing;

import com.uber.tchannel.api.handlers.TFutureCallback;
import com.uber.tchannel.handlers.OutRequest;
import com.uber.tchannel.messages.Request;
import com.uber.tchannel.messages.Response;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Tracing {

    private static final Logger logger = LoggerFactory.getLogger(Tracing.class);

    public static void startOutboundSpan(
            OutRequest outRequest,
            Tracer tracer,
            TracingContext tracingContext
    ) {
        if (tracer == null) {
            return;
        }
        Request request = outRequest.getRequest();
        Tracer.SpanBuilder builder = tracer.buildSpan(request.getEndpoint());
        if (tracingContext.hasSpan()) {
            Span parentSpan = tracingContext.currentSpan();
            builder.asChildOf(parentSpan.context());
        }
        // TODO add tags for peer host:port
        builder
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                .withTag(Tags.PEER_SERVICE.getKey(), request.getService())
                .withTag("as", request.getArgScheme().name());

        final Span span = builder.start();

        // TODO if tracer is Zipkin compatible, inject Trace fields
        // if request has headers, inject tracing context
        if (request instanceof TraceableRequest) {
            TraceableRequest traceableRequest = (TraceableRequest) request;
            //Format.Builtin.TEXT_MAP
            Map<String, String> headers = traceableRequest.getHeaders();
            PrefixedHeadersCarrier carrier = new PrefixedHeadersCarrier(headers);
            try {
                tracer.inject(span.context(), Format.Builtin.TEXT_MAP, carrier);
                traceableRequest.updateHeaders(headers);
            } catch (Exception e) {
                logger.error("Failed to inject span context into headers", e);
            }
        }
        outRequest.getFuture().addCallback(new TFutureCallback() {
            @Override
            public void onResponse(Response response) {
                if (response.isError()) {
                    Tags.ERROR.set(span, true);
                    span.log(response.getError().getMessage(), null);
                }
                span.finish();
            }
        });
    }

    public static Span startInboundSpan(
            Request request,
            Tracer tracer,
            TracingContext tracingContext) {
        SpanContext parentContext = null;
        if (request instanceof TraceableRequest) {
            TraceableRequest traceableRequest = (TraceableRequest) request;
            Map<String, String> headers = traceableRequest.getHeaders();
            PrefixedHeadersCarrier carrier = new PrefixedHeadersCarrier(headers);
            try {
                parentContext = tracer.extract(Format.Builtin.TEXT_MAP, carrier);
                Map<String, String> nonTracingHeaders = carrier.getNonTracingHeaders();
                if (nonTracingHeaders.size() < headers.size()) {
                    traceableRequest.updateHeaders(nonTracingHeaders);
                }
            } catch (Exception e) {
                logger.error("Failed to extract span context from headers", e);
            }
        } else {
            // TODO if tracer is Zipkin compatible, extract parent from Trace fields
        }
        Tracer.SpanBuilder builder = tracer.buildSpan(request.getEndpoint());
        builder
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
                .withTag("as", request.getArgScheme().name());
        Map<String, String> transportHeaders = request.getTransportHeaders();
        if (transportHeaders != null && transportHeaders.containsKey("cn")) {
            builder.withTag(Tags.PEER_SERVICE.getKey(), transportHeaders.get("cn"));
        }
        // TODO add tags for peer host:port
        if (parentContext != null) {
            builder.asChildOf(parentContext);
        }
        Span span = builder.start();
        tracingContext.clear();
        tracingContext.pushSpan(span);
        return span;
    }
}
