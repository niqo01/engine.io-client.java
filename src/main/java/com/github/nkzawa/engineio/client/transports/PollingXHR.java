package com.github.nkzawa.engineio.client.transports;


import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.thread.EventThread;
import com.squareup.okhttp.*;

import java.io.IOException;

import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

public class PollingXHR extends Polling {

    private static final Logger logger = Logger.getLogger(PollingXHR.class.getName());

    private final OkHttpClient client;

    public PollingXHR(Options opts) {
        super(opts);
        this.client = opts.httpClient;
        client.networkInterceptors().add(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                com.squareup.okhttp.Request request = chain.request();

                long t1 = System.nanoTime();
                logger.info(String.format("Sending request %s on %s%n%s",
                        request.url(), chain.connection(), request.headers()));

                Response response = chain.proceed(request);

                long t2 = System.nanoTime();
                logger.info(String.format("Received response for %s in %.1fms%n%s",
                        response.request().url(), (t2 - t1) / 1e6d, response.headers()));

                return response;
            }
        });
    }

    protected Request request() {
        return this.request(null);
    }

    protected Request request(Request.Options opts) {
        if (opts == null) {
            opts = new Request.Options();
        }
        opts.uri = this.uri();
        opts.httpClient = this.client;
        Request req = new Request(opts);

        final PollingXHR self = this;
        req.on(Request.EVENT_REQUEST_HEADERS, new Listener() {
            @Override
            public void call(Object... args) {
                // Never execute asynchronously for support to modify headers.
                self.emit(EVENT_REQUEST_HEADERS, args[0]);
            }
        }).on(Request.EVENT_RESPONSE_HEADERS, new Listener() {
            @Override
            public void call(final Object... args) {
                EventThread.exec(new Runnable() {
                    @Override
                    public void run() {
                        self.emit(EVENT_RESPONSE_HEADERS, args[0]);
                    }
                });
            }
        });
        return req;
    }

    @Override
    protected void doWrite(byte[] data, final Runnable fn) {
        Request.Options opts = new Request.Options();
        opts.method = "POST";
        opts.data = data;
        Request req = this.request(opts);
        final PollingXHR self = this;
        req.on(Request.EVENT_SUCCESS, new Listener() {
            @Override
            public void call(Object... args) {
                EventThread.exec(new Runnable() {
                    @Override
                    public void run() {
                        fn.run();
                    }
                });
            }
        });
        req.on(Request.EVENT_ERROR, new Listener() {
            @Override
            public void call(final Object... args) {
                EventThread.exec(new Runnable() {
                    @Override
                    public void run() {
                        Exception err = args.length > 0 && args[0] instanceof Exception ? (Exception)args[0] : null;
                        self.onError("xhr post error", err);
                    }
                });
            }
        });
        req.create();
    }

    @Override
    protected void doPoll() {
        logger.fine("xhr poll");
        Request req = this.request();
        final PollingXHR self = this;
        req.on(Request.EVENT_DATA, new Listener() {
            @Override
            public void call(final Object... args) {
                EventThread.exec(new Runnable() {
                    @Override
                    public void run() {
                        Object arg = args.length > 0 ? args[0] : null;
                        if (arg instanceof String) {
                            self.onData((String)arg);
                        } else if (arg instanceof byte[]) {
                            self.onData((byte[])arg);
                        }
                    }
                });
            }
        });
        req.on(Request.EVENT_ERROR, new Listener() {
            @Override
            public void call(final Object... args) {
                EventThread.exec(new Runnable() {
                    @Override
                    public void run() {
                        Exception err = args.length > 0 && args[0] instanceof Exception ? (Exception) args[0] : null;
                        self.onError("xhr poll error", err);
                    }
                });
            }
        });
        req.create();
    }

    public static class Request extends Emitter {

        public static final String EVENT_SUCCESS = "success";
        public static final String EVENT_DATA = "data";
        public static final String EVENT_ERROR = "error";
        public static final String EVENT_REQUEST_HEADERS = "requestHeaders";
        public static final String EVENT_RESPONSE_HEADERS = "responseHeaders";

        private String method;
        private String uri;

        // data is always a binary
        private byte[] data;

        private OkHttpClient client;
        private Call call;

        public Request(Options opts) {
            this.method = opts.method != null ? opts.method : "GET";
            this.uri = opts.uri;
            this.data = opts.data;
            this.client = opts.httpClient;
        }

        public void create() {
            final Request self = this;

            logger.fine(String.format("xhr open %s: %s", this.method, this.uri));


            com.squareup.okhttp.Request.Builder requestBuilder = new com.squareup.okhttp.Request.Builder()
                    .url(this.uri);


            if (self.data != null) {
                RequestBody requestBody = RequestBody.create(MediaType.parse("application/octet-stream"), self.data);
                requestBuilder.method(this.method, requestBody);
            }

            Map<String, String> headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            self.onRequestHeaders(headers);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                requestBuilder.addHeader(header.getKey(), header.getValue());
            }

            call = client.newCall(requestBuilder.build());
            call.enqueue(new Callback() {
                @Override
                public void onFailure(com.squareup.okhttp.Request request, IOException e) {
                    self.onError(e);
                }

                @Override
                public void onResponse(Response response) throws IOException {
                    Map<String, String> headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);

                    Headers responseHeaders = response.headers();
                    for (int i = 0; i < responseHeaders.size(); i++) {
                        headers.put(responseHeaders.name(i), responseHeaders.value(i));
                    }

                    self.onResponseHeaders(headers);
                    final int statusCode = response.code();
                    if (response.isSuccessful()) {
                        self.onLoad(response);
                    } else {
                        self.onError(new IOException(Integer.toString(statusCode)));
                    }
                }
            });

            logger.fine(String.format("sending xhr with url %s | data %s", this.uri, this.data));
        }

        private void onSuccess() {
            this.emit(EVENT_SUCCESS);
            this.cleanup();
        }

        private void onData(String data) {
            this.emit(EVENT_DATA, data);
            this.onSuccess();
        }

        private void onData(byte[] data) {
            this.emit(EVENT_DATA, data);
            this.onSuccess();
        }

        private void onError(Exception err) {
            this.emit(EVENT_ERROR, err);
            this.cleanup();
        }

        private void onRequestHeaders(Map<String, String> headers) {
            this.emit(EVENT_REQUEST_HEADERS, headers);
        }

        private void onResponseHeaders(Map<String, String> headers) {
            this.emit(EVENT_RESPONSE_HEADERS, headers);
        }

        private void cleanup() {
            if (call == null) {
                return;
            }

            call.cancel();
            call = null;
        }

        private void onLoad(Response response) {
            MediaType mediaType = response.body().contentType();
            try {
                if ("application".equalsIgnoreCase(mediaType.type())
                        && "octet-stream".equalsIgnoreCase(mediaType.subtype())) {
                    this.onData(response.body().bytes());
                } else {
                    this.onData(response.body().string());
                }
            } catch (IOException e) {
                this.onError(e);
            }
        }
        public void abort() {
            this.cleanup();
        }

        public static class Options {

            public String uri;
            public String method;
            public byte[] data;
            public OkHttpClient httpClient;
        }
    }
}
