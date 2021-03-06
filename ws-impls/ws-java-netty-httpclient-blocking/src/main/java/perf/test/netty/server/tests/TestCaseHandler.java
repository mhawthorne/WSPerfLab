package perf.test.netty.server.tests;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.codehaus.jackson.JsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import perf.test.netty.PropertyNames;
import perf.test.netty.client.PoolExhaustedException;
import perf.test.netty.server.RequestProcessingFailedException;
import perf.test.netty.server.RequestProcessingPromise;
import perf.test.netty.server.ServerHandler;
import perf.test.netty.server.StatusRetriever;
import perf.test.utils.BackendResponse;
import perf.test.utils.EventLogger;
import perf.test.utils.PerformanceLogger;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * @author Nitesh Kant (nkant@netflix.com)
 * @author mhawthorne
 */
public abstract class TestCaseHandler {

    private static final Pattern HOSTS_SPLITTER = Pattern.compile(",");
    private static final Logger logger = LoggerFactory.getLogger(TestCaseHandler.class);

    private final String testCaseName;
    protected final static JsonFactory jsonFactory = new JsonFactory();

    private final HttpClient client;

    private final AtomicLong testWithErrors = new AtomicLong();
    private final AtomicLong inflightTests = new AtomicLong();
    private final AtomicLong requestRecvCount = new AtomicLong();

    final int maxBackendThreadPoolSize = PropertyNames.BackendRequestThreadPoolSize.getValueAsInt();
    private final ScheduledExecutorService requestExecutor =
        new ScheduledThreadPoolExecutor(maxBackendThreadPoolSize) {{
        this.setMaximumPoolSize(maxBackendThreadPoolSize);
    }};


    private final HostSelector hostSelector;

    private static interface HostSelector {
        String next();
    }

    private static final HostSelector newHostSelector(String... hosts) {
        return new RandomHostSelector(hosts);
    }

    private static final class RoundRobinHostSelector implements HostSelector {

        private final String[] hosts;
        private final int hostCount;

        // trying to store the index in a thread local so that multiple threads won't contend
        // I can't tell if this is a stupid way to handle this problem or not, my brain isn't working today
        private static final ThreadLocal<Integer> localIndex = new ThreadLocal<Integer>() {
            @Override
            protected Integer initialValue() {
                return 0;
            }
        };

        RoundRobinHostSelector(String ... hosts) {
            this.hosts = hosts;
            this.hostCount = hosts.length;
        }

        public String next() {
            int idx = localIndex.get();
            if(idx == hostCount)
                idx = 0;
            final String host = hosts[idx];
            localIndex.set(++idx);
            return host;
        }
    }

    private static final class RandomHostSelector implements HostSelector {

        private final String[] hosts;
        private final int hostCount;

        RandomHostSelector(String ... hosts) {
            this.hosts = hosts;
            this.hostCount = hosts.length;
        }

        @Override
        public String next() {
            final int idx = (int) Math.floor(Math.random() * this.hostCount);
            return this.hosts[idx];
        }
    }

    protected TestCaseHandler(String testCaseName, EventLoopGroup eventLoopGroup) {
        this.testCaseName = testCaseName;

        String hosts = PropertyNames.MockBackendHost.getValueAsString();
        String[] splitHosts = HOSTS_SPLITTER.split(hosts);
        int serverPort = PropertyNames.MockBackendPort.getValueAsInt();

        this.hostSelector = newHostSelector(splitHosts);

        final RequestConfig reqConfig = RequestConfig.custom()
            .setConnectTimeout(PropertyNames.ClientConnectTimeout.getValueAsInt())
            .setSocketTimeout(PropertyNames.ClientSocketTimeout.getValueAsInt())
            .setConnectionRequestTimeout(PropertyNames.ClientConnectionRequestTimeout.getValueAsInt())
            .build();

        // don't care about total vs. per-route right now, will set them to the same
        final PoolingHttpClientConnectionManager connMgr = new PoolingHttpClientConnectionManager();
        connMgr.setMaxTotal(PropertyNames.ClientMaxConnectionsTotal.getValueAsInt());
        connMgr.setDefaultMaxPerRoute(PropertyNames.ClientMaxConnectionsTotal.getValueAsInt());

        this.client = HttpClients.custom()
            .setDefaultRequestConfig(reqConfig)
            .setConnectionManager(connMgr)
            .build();
    }

    protected static String constructUri(String type, int numItems, int itemSize, int delay) {
        String uri = String.format("/mock.json?type=%s&numItems=%d&itemSize=%d&delay=%d&id=", type, numItems, itemSize, delay);
        if (logger.isDebugEnabled()) {
            logger.debug("Created a new uri: " + uri);
        }
        return uri;
    }

    public void processRequest(Channel channel, EventExecutor executor, HttpRequest request, QueryStringDecoder qpDecoder,
                               RequestProcessingPromise requestProcessingPromise) {
        inflightTests.incrementAndGet();
        requestRecvCount.incrementAndGet();
        requestProcessingPromise.addListener(new GenericFutureListener<Future<? super FullHttpResponse>>() {
            @Override
            public void operationComplete(Future<? super FullHttpResponse> future) throws Exception {
                inflightTests.decrementAndGet();
            }
        });

        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        Map<String,List<String>> parameters = qpDecoder.parameters();
        List<String> id = parameters.get("id");
        if (null == id || id.isEmpty()) {
            requestProcessingPromise.tryFailure(new RequestProcessingFailedException(HttpResponseStatus.BAD_REQUEST, new IllegalArgumentException( "query parameter id not provided.")));
        } else {
            try {
                String thisId = id.get(0);
                requestProcessingPromise.setTestCaseId(thisId);
                executeTestCase(channel, executor, keepAlive, thisId, requestProcessingPromise);
            } catch (Throwable throwable) {
                logger.error("Test case execution failed.", throwable);
                testWithErrors.incrementAndGet();
                requestProcessingPromise.tryFailure(new RequestProcessingFailedException(HttpResponseStatus.INTERNAL_SERVER_ERROR,throwable));
            }
        }
    }

    protected abstract void executeTestCase(Channel channel, EventExecutor executor, boolean keepAlive, String id,
                                            RequestProcessingPromise requestProcessingPromise);

    public void dispose() {
//        clientFactory.shutdown();
    }

    public String getTestCaseName() {
        return testCaseName;
    }

    protected void get(String reqId, EventExecutor eventExecutor, String path,
        final GenericFutureListener<Future<FullHttpResponse>> responseHandler) {
        this.asyncGet(reqId, eventExecutor, path, responseHandler);
//        return this.blockingGet(reqId, eventExecutor, path, responseHandler);
    }

    protected Future<FullHttpResponse> blockingGet(String reqId, EventExecutor eventExecutor, String path,
        final GenericFutureListener<Future<FullHttpResponse>> responseHandler) {
        return this.httpClientGet(reqId, eventExecutor, path, responseHandler);
    }

    // "async" meaning blocking IO run in a thread pool
    // this code is hideous and I have no idea what I am doing
    protected java.util.concurrent.Future<Future<FullHttpResponse>> asyncGet(final String reqId,
        final EventExecutor eventExecutor,
        final String path,
        final GenericFutureListener<Future<FullHttpResponse>> responseHandler) {
        EventLogger.log(reqId, "backend-request-submit " + path);
        return this.requestExecutor.submit(new Callable<Future<FullHttpResponse>> () {
            @Override
            public Future<FullHttpResponse> call() throws Exception {
                return blockingGet(reqId, eventExecutor, path, responseHandler);
            }
        });
    }

    private Future<FullHttpResponse> httpClientGet(String reqId, EventExecutor eventExecutor, String path,
                                           final GenericFutureListener<Future<FullHttpResponse>> responseHandler) {
        Preconditions.checkNotNull(eventExecutor, "Event executor can not be null");

        final PerformanceLogger perfLogger = PerformanceLogger.instance();

        String basePath = PropertyNames.MockBackendContextPath.getValueAsString();
        path = basePath + path;

        final String host = this.hostSelector.next();

        final String uri = "http://" + host + ":" +
        PropertyNames.MockBackendPort.getValueAsString() + path;
//            logger.debug("backend request URI: " + uri);

        EventLogger.log(reqId, "backend-request-start " + uri);
        final String perfKey = "backend-request " + uri;
        perfLogger.start(reqId, perfKey);


        InputStream originResStream = null;
        try {
            final HttpUriRequest originReq = new HttpGet(uri);
            final HttpResponse originRes = (HttpResponse) this.client.execute(originReq);
            final DefaultPromise<FullHttpResponse> promise = new DefaultPromise<FullHttpResponse>(eventExecutor);
            promise.addListener(responseHandler);


            final ByteBuf nettyResBytes = Unpooled.buffer();

            originResStream = originRes.getEntity().getContent();
            final byte[] b = new byte[1024];
            int read = -1;
            while((read = originResStream.read(b)) != -1) {
                nettyResBytes.writeBytes(b, 0, read);
            }

            final DefaultFullHttpResponse nettyRes = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(originRes.getStatusLine().getStatusCode()),
                nettyResBytes);
            promise.trySuccess(nettyRes);

            return promise;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if(originResStream != null) {
                try {
                    originResStream.close();
                } catch (IOException e) {}
            }

            perfLogger.stop(reqId, perfKey);
            EventLogger.log(reqId, "backend-request-end " + uri);;
        }
    }

//    private Future<FullHttpResponse> nettyGet(EventExecutor eventExecutor, String path,
//                                           final GenericFutureListener<Future<FullHttpResponse>> responseHandler) {
//        Preconditions.checkNotNull(eventExecutor, "Event executor can not be null");
//        String basePath = PropertyNames.MockBackendContextPath.getValueAsString();
//        path = basePath + path;
//        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
//        return httpClient.execute(eventExecutor, request).addListener(responseHandler);
//    }

    public void populateStatus(StatusRetriever.Status statusToPopulate) {
        StatusRetriever.TestCaseStatus testCaseStatus = new StatusRetriever.TestCaseStatus();

//        httpClient.populateStatus(testCaseStatus);

        ServerHandler.populateStatus(testCaseStatus);
        testCaseStatus.setInflightTests(inflightTests.get());
        testCaseStatus.setRequestRecvCount(requestRecvCount.get());
        testCaseStatus.setTestWithErrors(testWithErrors.get());
        statusToPopulate.addTestStatus(testCaseName, testCaseStatus);
    }

    public void populateTrace(StringBuilder traceBuilder) {
//        httpClient.populateTrace(traceBuilder);
    }



    static class ResponseCollector {

        final BackendResponse[] responses = new BackendResponse[5];

        static final int RESPONSE_A_INDEX = 0;
        static final int RESPONSE_B_INDEX = 1;
        static final int RESPONSE_C_INDEX = 2;
        static final int RESPONSE_D_INDEX = 3;
        static final int RESPONSE_E_INDEX = 4;
    }

    protected abstract static class CompletionListener implements GenericFutureListener<Future<FullHttpResponse>> {

        private final ResponseCollector responseCollector;
        private final int responseIndex;
        private final RequestProcessingPromise topLevelRequestCompletionPromise;

        protected CompletionListener(ResponseCollector responseCollector, int responseIndex, RequestProcessingPromise topLevelRequestCompletionPromise) {
            this.responseCollector = responseCollector;
            this.responseIndex = responseIndex;
            this.topLevelRequestCompletionPromise = topLevelRequestCompletionPromise;
        }

        @Override
        public void operationComplete(Future<FullHttpResponse> future) throws Exception {
            if (future.isSuccess()) {
                if (PropertyNames.ServerTraceRequests.getValueAsBoolean()) {
                    topLevelRequestCompletionPromise.checkpoint("Call success for response index: " + responseIndex);
                }
                FullHttpResponse response = future.get();
                HttpResponseStatus status = response.getStatus();
                if (status.equals(HttpResponseStatus.OK)) {
                    ByteBuf responseContent = response.content();
                    if (responseContent.isReadable()) {
                        String content = responseContent.toString(CharsetUtil.UTF_8);
                        responseContent.release();
                        try {
                            responseCollector.responses[responseIndex] = BackendResponse.fromJson(jsonFactory, content);
                            onResponseReceived();
                        } catch (Exception e) {
                            logger.error("Failed to parse the received backend response.", e);
                            topLevelRequestCompletionPromise.tryFailure(new RequestProcessingFailedException(HttpResponseStatus.INTERNAL_SERVER_ERROR, e));
                        }
                    }
                } else {
                    if (PropertyNames.ServerTraceRequests.getValueAsBoolean()) {
                        topLevelRequestCompletionPromise.checkpoint("Call failed for response index: " + responseIndex + ", error: " + future.cause());
                    }
                    topLevelRequestCompletionPromise.tryFailure(new RequestProcessingFailedException(status));
                }
            } else {
                HttpResponseStatus status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                Throwable cause = future.cause();
                if (cause instanceof PoolExhaustedException) {
                    status = HttpResponseStatus.SERVICE_UNAVAILABLE;
                }
                topLevelRequestCompletionPromise.tryFailure(new RequestProcessingFailedException(status, cause));

            }
        }

        protected abstract void onResponseReceived();
    }

}
