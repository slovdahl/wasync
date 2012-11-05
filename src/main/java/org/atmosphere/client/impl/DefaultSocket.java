/*
 * Copyright 2012 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.client.impl;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.websocket.WebSocket;
import org.atmosphere.client.Encoder;
import org.atmosphere.client.Function;
import org.atmosphere.client.FunctionWrapper;
import org.atmosphere.client.Future;
import org.atmosphere.client.Request;
import org.atmosphere.client.Socket;
import org.atmosphere.client.Transport;
import org.atmosphere.client.transport.LongPollingTransport;
import org.atmosphere.client.transport.SSETransport;
import org.atmosphere.client.transport.StreamTransport;
import org.atmosphere.client.transport.WebSocketTransport;
import org.atmosphere.client.util.ReaderInputStream;
import org.atmosphere.client.util.TypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DefaultSocket implements Socket {

    private final Logger logger = LoggerFactory.getLogger(DefaultSocket.class);

    private Request request;
    private InternalSocket socket;
    private final List<FunctionWrapper> functions = new ArrayList<FunctionWrapper>();
    private final AsyncHttpClient asyncHttpClient;
    private Transport transportInUse;

    public DefaultSocket(AsyncHttpClient asyncHttpClient) {
        this.asyncHttpClient = asyncHttpClient;
    }

    public Future fire(Object data) throws IOException {
        socket.write(request, data);
        return new Future(this);
    }

    public Socket on(Function<? extends Object> function) {
        functions.add(new FunctionWrapper("", function));
        return this;
    }

    public Socket on(String functionName, Function<? extends Object> function) {
        functions.add(new FunctionWrapper(functionName, function));
        return this;
    }

    public Socket open(Request request) throws IOException {
        this.request = request;
        RequestBuilder r = new RequestBuilder();
        r.setUrl(request.uri())
                .setMethod(request.method().name())
                .setHeaders(request.headers());

        List<Transport> transports = getTransport(request);

        return connect(r, transports);
    }

    protected Socket connect(RequestBuilder r,  List<Transport> transports) throws IOException {

        if (transports.size() > 0) {
            transportInUse = transports.remove(0);
        } else {
            throw new IOException("No suitable transport supported");
        }

        Future f = new Future(this);
        transportInUse.future(f);
        if (transportInUse.name().equals(Request.TRANSPORT.WEBSOCKET)) {
            r.setUrl(request.uri().replace("http", "ws"));
            try {
                java.util.concurrent.Future<WebSocket> w = asyncHttpClient.prepareRequest(r.build()).execute(
                        (AsyncHandler<WebSocket>) transportInUse);

                socket = new InternalSocket(w.get());
            } catch (ExecutionException t) {
                Throwable e = t.getCause();
                if (e != null) {
                    if (e.getMessage() != null && e.getMessage().equalsIgnoreCase("Invalid handshake response")) {
                        logger.info("WebSocket not supported, downgrading to an HTTP based transport.");
                        return connect(r, transports);
                    }
                }
                transportInUse.onThrowable(t);
                return new VoidSocket();
            } catch (Throwable t) {
                transportInUse.onThrowable(t);
                return new VoidSocket();
            }
        } else {
            r.setUrl(request.uri().replace("ws", "http"));
            java.util.concurrent.Future<String> s = asyncHttpClient.prepareRequest(r.build()).execute(
                    (AsyncHandler<String>) transportInUse);

            try {
                // TODO: Give a chance to connect and then unlock. With Atmosphere we will received junk at the
                // beginning for streaming and sse, but nothing for long-polling
                f.get(2500, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                // Swallow  LOG ME
            }

            socket = new InternalSocket(asyncHttpClient);
        }
        return this;
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
            transportInUse.close();
        }
    }

    protected List<Transport> getTransport(Request request) throws IOException {
        List<Transport> transports = new ArrayList<Transport>();

        for (Request.TRANSPORT t : request.transport()) {
            if (t.equals(Request.TRANSPORT.WEBSOCKET)) {
                transports.add(new WebSocketTransport(request.decoders(), functions, request.functionResolver()));
            } else if (t.equals(Request.TRANSPORT.SSE)) {
                transports.add(new SSETransport(request.decoders(), functions, request.functionResolver()));
            } else if (t.equals(Request.TRANSPORT.LONG_POLLING)) {
                transports.add(new LongPollingTransport(request.decoders(), functions, request, asyncHttpClient, request.functionResolver()));
            } else if (t.equals(Request.TRANSPORT.STREAMING)) {
                transports.add(new StreamTransport(request.decoders(), functions, request.functionResolver()));
            }
        }
        return transports;
    }


    private final static class InternalSocket {

        private final WebSocket webSocket;
        private final AsyncHttpClient asyncHttpClient;

        public InternalSocket(WebSocket webSocket) {
            this.webSocket = webSocket;
            this.asyncHttpClient = null;
        }

        public InternalSocket(AsyncHttpClient asyncHttpClient) {
            this.webSocket = null;
            this.asyncHttpClient = asyncHttpClient;
        }

        public void close() {
            if (webSocket != null) {
                webSocket.close();
            } else {
                asyncHttpClient.close();
            }
        }

        Object invokeEncoder(List<Encoder<? extends Object, ?>> encoders, Object instanceType) {
            for (Encoder e : encoders) {
                Class<?>[] typeArguments = TypeResolver.resolveArguments(e.getClass(), Encoder.class);

                if (typeArguments.length > 0 && typeArguments[0].equals(instanceType.getClass())) {
                    instanceType = e.encode(instanceType);
                }
            }
            return instanceType;
        }

        public InternalSocket write(Request request, Object data) throws IOException {

            // Execute encoder
            Object object = invokeEncoder(request.encoders(), data);
            if (webSocket != null) {
                if (InputStream.class.isAssignableFrom(object.getClass())) {
                    InputStream is = (InputStream) object;
                    ByteArrayOutputStream bs = new ByteArrayOutputStream();
                    //TODO: We need to stream directly, in AHC!
                    byte[] buffer = new byte[8192];
                    int n = 0;
                    while (-1 != (n = is.read(buffer))) {
                        bs.write(buffer, 0, n);
                    }
                    webSocket.sendMessage(bs.toByteArray());
                } else if (Reader.class.isAssignableFrom(object.getClass())) {
                    Reader is = (Reader) object;
                    StringWriter bs = new StringWriter();
                    //TODO: We need to stream directly, in AHC!
                    char[] chars = new char[8192];
                    int n = 0;
                    while (-1 != (n = is.read(chars))) {
                        bs.write(chars, 0, n);
                    }
                    webSocket.sendTextMessage(bs.getBuffer().toString());
                } else if (String.class.isAssignableFrom(object.getClass())) {
                    webSocket.sendTextMessage(object.toString());
                } else if (byte[].class.isAssignableFrom(object.getClass())) {
                    webSocket.sendMessage((byte[]) object);
                } else {
                    throw new IllegalStateException("No Encoder for " + data);
                }
            } else {
                if (InputStream.class.isAssignableFrom(object.getClass())) {
                    //TODO: Allow reading the response.
                    asyncHttpClient.preparePost(request.uri())
                            .setMethod(Request.METHOD.POST.name())
                            .setBody((InputStream) object).execute();
                } else if (Reader.class.isAssignableFrom(object.getClass())) {
                    asyncHttpClient.preparePost(request.uri())
                            .setMethod(Request.METHOD.POST.name())
                            .setBody(new ReaderInputStream((Reader) object))
                            .execute();
                    return this;
                } else if (String.class.isAssignableFrom(object.getClass())) {
                    asyncHttpClient.preparePost(request.uri())
                            .setMethod(Request.METHOD.POST.name())
                            .setBody((String) object)
                            .execute();
                } else if (byte[].class.isAssignableFrom(object.getClass())) {
                    asyncHttpClient.preparePost(request.uri())
                            .setMethod(Request.METHOD.POST.name())
                            .setBody((byte[]) object)
                            .execute();
                } else {
                    throw new IllegalStateException("No Encoder for " + data);
                }
            }
            return this;
        }
    }


    private final static class VoidSocket implements Socket {

        @Override
        public Future fire(Object data) throws IOException {
            throw new IllegalStateException("An error occured during connection. Please add a Function(Throwable) to debug.");
        }

        @Override
        public Socket on(Function<? extends Object> function) {
            throw new IllegalStateException("An error occured during connection. Please add a Function(Throwable) to debug.");
        }

        @Override
        public Socket on(String functionMessage, Function<? extends Object> function) {
            throw new IllegalStateException("An error occured during connection. Please add a Function(Throwable) to debug.");
        }

        @Override
        public Socket open(Request request) throws IOException {
            throw new IllegalStateException("An error occured during connection. Please add a Function(Throwable) to debug.");
        }

        @Override
        public void close() {
            throw new IllegalStateException("An error occured during connection. Please add a Function(Throwable) to debug.");
        }
    }
}