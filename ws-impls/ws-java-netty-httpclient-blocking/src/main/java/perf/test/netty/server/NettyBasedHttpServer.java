package perf.test.netty.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import perf.test.netty.ConnectedClientsCounter;
import perf.test.netty.ProcessingTimesStartInterceptor;
import perf.test.netty.PropertyNames;
import perf.test.netty.client.PoolExhaustedException;
import perf.test.netty.server.tests.TestRegistry;

import java.net.InetSocketAddress;

/**
 * A simple server based on netty. The server starts on port as specified by {@link PropertyNames#ServerPort}.
 *
 * @author Nitesh Kant (nkant@netflix.com)
 */
public class NettyBasedHttpServer {

    private final Logger logger = LoggerFactory.getLogger(NettyBasedHttpServer.class);

    private final int port;
    private ServerBootstrap bootstrap;
    private ConnectedClientsCounter connectedClientsCounter;

    public NettyBasedHttpServer() {
        port = PropertyNames.ServerPort.getValueAsInt();
    }

    /**
     * This instantiates netty's {@link perf.test.netty.server.ServerBootstrap} and the {@link TestRegistry}
     *
     * @throws InterruptedException If the {@link TestRegistry} was interrupted during startup.
     */
    public void start() throws InterruptedException, PoolExhaustedException {
        bootstrap = new ServerBootstrap();
        connectedClientsCounter = new ConnectedClientsCounter();
        final StatusRetriever statusRetriever = new StatusRetriever(connectedClientsCounter);
        final EventLoopGroup serverEventLoopGrp;
        final Class<? extends ServerSocketChannel> channelClass;
        if (PropertyNames.ServerIOBlocking.getValueAsBoolean()) {
            channelClass = OioServerSocketChannel.class;
            serverEventLoopGrp = new OioEventLoopGroup(PropertyNames.ServerEventLoopCount.getValueAsInt());
        } else {
            channelClass = NioServerSocketChannel.class;
            serverEventLoopGrp = new NioEventLoopGroup(PropertyNames.ServerEventLoopCount.getValueAsInt());
        }

        final EventExecutorGroup group = new DefaultEventExecutorGroup(PropertyNames.ServerHandlerEventExecutorGroupSize.getValueAsInt());

        bootstrap.group(serverEventLoopGrp)
                 .channel(channelClass).childOption(ChannelOption.SO_KEEPALIVE, true)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     protected void initChannel(SocketChannel ch) throws Exception {
                         ChannelPipeline pipeline = ch.pipeline();
                         pipeline.addFirst("clientCounter", connectedClientsCounter);
                         if (PropertyNames.ServerLoggingEnable.getValueAsBoolean()) {
                             pipeline.addLast("logger", new LoggingHandler(LogLevel.DEBUG));
                         }
                         pipeline.addLast("decoder", new HttpRequestDecoder());
                         pipeline.addLast("aggregator",
                                          new HttpObjectAggregator(PropertyNames.ServerChunkSize.getValueAsInt()));
                         pipeline.addFirst("timingStart", new ProcessingTimesStartInterceptor());
                         pipeline.addLast("encoder", new HttpResponseEncoder());
                         pipeline.addLast(group,
                                          "handler",
                                          new ServerHandler(statusRetriever,
                                                            PropertyNames.ServerContextPath.getValueAsString()));
                         pipeline.addLast("timingEnd", new ProcessingTimeEndInterceptor());
                     }
                 });
        bootstrap.bind(new InetSocketAddress(port));
        final EventLoopGroup clientEventLoopGrp;
        if (PropertyNames.ClientIOBlocking.getValueAsBoolean()) {
            clientEventLoopGrp = new OioEventLoopGroup(PropertyNames.ClientEventLoopCount.getValueAsInt());
        } else {
            clientEventLoopGrp = new NioEventLoopGroup(PropertyNames.ClientEventLoopCount.getValueAsInt());
        }
        TestRegistry.init(clientEventLoopGrp);

        logger.info("Netty server started at port: " + port);
    }

    public void stop() {
        TestRegistry.shutdown();
        bootstrap.childGroup().shutdownGracefully();
        bootstrap.group().shutdownGracefully();
    }
}
