package perf.test.netty.client;

import com.google.common.base.Preconditions;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import perf.test.netty.server.StatusRetriever;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 *
 * @author Nitesh Kant
 */
class DedicatedClientPool<T, R extends HttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(DedicatedClientPool.class);

    public static final String RESPONSE_HANDLER_ATTR_KEY_NAME = "response_handler";
    public static final String PROCESSING_COMPLETE_PROMISE_KEY_NAME = "processing_complete_promise";
    public static final AttributeKey<AtomicInteger> RETRY_COUNT_KEY = new AttributeKey<AtomicInteger>("retry_count");

    private final String keyPrefix;
    private final AttributeKey<Promise<T>> processingCompletePromiseKey;
    private final AttributeKey<GenericFutureListener<Future<T>>> responseHandlerKey;

    protected final Bootstrap bootstrap;
    protected final InetSocketAddress serverAddress;

    private final AtomicInteger unhandledRequests = new AtomicInteger();

    private final int maxConnections;

    private final LinkedBlockingQueue<Object> clientLimitEnforcer;
    private final ConcurrentLinkedQueue<DedicatedHttpClient<T,R>> availableClients; // unbounded as the # of conn is bounded by allClients queue.
    private final int coreConnections;

    DedicatedClientPool(InetSocketAddress serverAddress, Bootstrap bootstrap, int maxConnections, int coreConnections) {
        keyPrefix = serverAddress.getHostName() + ':' + serverAddress.getPort();
        responseHandlerKey = new AttributeKey<GenericFutureListener<Future<T>>>(keyPrefix + RESPONSE_HANDLER_ATTR_KEY_NAME);
        processingCompletePromiseKey = new AttributeKey<Promise<T>>(keyPrefix + PROCESSING_COMPLETE_PROMISE_KEY_NAME);

        this.coreConnections = coreConnections;
        Preconditions.checkArgument(coreConnections <= maxConnections,
                                    "Core connection count can not be more than max connections.");
        this.maxConnections = maxConnections;
        this.bootstrap = bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        this.serverAddress = serverAddress;
        clientLimitEnforcer = new LinkedBlockingQueue<Object>(this.maxConnections);
        availableClients = new ConcurrentLinkedQueue<DedicatedHttpClient<T, R>>();
    }

    void init() {
        for (int i = 0; i < coreConnections; i++) {
            createNewClientEagerly();
        }
    }

    private void createNewClientEagerly() {
        createNewClientOnDemand(null);
    }

    private Promise<DedicatedHttpClient<T, R>> createNewClientOnDemand(@Nullable final Promise<DedicatedHttpClient<T, R>> completionPromise) {
        final Object clientLimitEnforcingToken = new Object();
        if (clientLimitEnforcer.offer(clientLimitEnforcingToken)) {
            bootstrap.connect(serverAddress).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        logger.debug("New client connected for host {} and port {}", serverAddress.getHostName(),
                                     serverAddress.getPort());
                        final DedicatedHttpClient<T, R> httpClient = getHttpClient(future.channel());
                        future.channel().closeFuture().addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) throws Exception {
                                logger.debug("Client disconnected from host {} and port {}",
                                             serverAddress.getHostName(),
                                             serverAddress.getPort());
                                clientLimitEnforcer.remove(clientLimitEnforcingToken);
                                availableClients.remove(httpClient);
                            }
                        });
                        if (null == completionPromise) {
                            addAvailableClient(httpClient);
                        } else {
                            completionPromise.setSuccess(httpClient);
                        }
                    } else {
                        clientLimitEnforcer.remove(clientLimitEnforcingToken);
                        logger.error(String.format("Failed to connect to host %s and port %d",
                                                   serverAddress.getHostName(),
                                                   serverAddress.getPort()), future.cause());
                        if (null != completionPromise) {
                            completionPromise.setFailure(future.cause());
                        }
                    }
                }
            });
        } else {
            if (null == completionPromise) {
                logger.error(
                        "Eager connection attempt failed. Pool exhausted, can not create any more connection to host {} and port {}.",
                        serverAddress.getHostName(),
                        serverAddress.getPort());
            } else {
                logger.error(
                        "On demand connection attempt failed. Pool exhausted, can not create any more connection to host {} and port {}.",
                        serverAddress.getHostName(),
                        serverAddress.getPort());
                completionPromise.setFailure(new PoolExhaustedException(serverAddress, maxConnections));
            }
        }
        return completionPromise;
    }

    private void addAvailableClient(DedicatedHttpClient<T, R> httpClient) {
        availableClients.add(httpClient);
    }

    Future<DedicatedHttpClient<T, R>> getClient(EventExecutor executor) {
        int retryCount = 0;
        while (true) {
            @Nullable DedicatedHttpClient<T, R> availableClient = availableClients.poll();
            final Promise<DedicatedHttpClient<T, R>> clientCreationPromise = new DefaultPromise<DedicatedHttpClient<T, R>>(executor);
            if (null == availableClient) {
                return createNewClientOnDemand(clientCreationPromise);
            } else if(availableClient.isActive()){
                clientCreationPromise.setSuccess(availableClient);
                return clientCreationPromise;
            } else {
                logger.info("Got an inactive client from available pool. Throwing it away. Retry count: " + retryCount);
                retryCount++;
                continue;
            }
        }
    }

    AttributeKey<GenericFutureListener<Future<T>>> getResponseHandlerKey() {
        return responseHandlerKey;
    }

    AttributeKey<Promise<T>> getProcessingCompletePromiseKey() {
        return processingCompletePromiseKey;
    }

    void returnClient(DedicatedHttpClient<T, R> clientToReturn) {
        if (clientToReturn.isActive()) {
            availableClients.add(clientToReturn);
        } else {
            logger.info("Inactive client returned, not adding back to the pool.");
        }
    }

    public void populateStatus(StatusRetriever.TestCaseStatus testCaseStatus) {
        StatusRetriever.ConnPoolStatus connPoolStatus = new StatusRetriever.ConnPoolStatus();
        testCaseStatus.addConnPoolStats(serverAddress, connPoolStatus);
        connPoolStatus.setAvailableConnectionsCount(availableClients.size());
        connPoolStatus.setTotalConnectionsCount(clientLimitEnforcer.size());
        connPoolStatus.setUnhandledRequestsSinceStartUp(unhandledRequests.get());
    }

    void onUnhandledRequest() {
        unhandledRequests.incrementAndGet();
    }

    protected DedicatedHttpClient<T, R> getHttpClient(Channel channel) {
        return new DedicatedHttpClient<T, R>(channel, serverAddress.getHostName(), this);
    }

    public void shutdown() {
    }

    public void populateTrace(StringBuilder traceBuilder) {
        // TODO: Populate trace.
    }
}
