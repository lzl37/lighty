package io.github.light0x00.letty.expr;


import io.github.light0x00.letty.expr.concurrent.FutureListener;
import io.github.light0x00.letty.expr.concurrent.ListenableFutureTask;
import io.github.light0x00.letty.expr.eventloop.NioEventLoopGroup;
import io.github.light0x00.letty.expr.handler.Acceptor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

/**
 * @author light0x00
 * @since 2023/6/28
 */
@Slf4j
public class Server {

    final NioEventLoopGroup parent;

    final NioEventLoopGroup child;

    final ChannelConfigurationProvider configProvider;

    final Acceptor acceptor;

    public Server(NioEventLoopGroup group, ChannelConfigurationProvider messageHandler) {
        this(group, group, messageHandler);
    }

    public Server(NioEventLoopGroup parent, NioEventLoopGroup child, ChannelConfigurationProvider configProvider) {
        this.parent = parent;
        this.child = child;
        this.configProvider = configProvider;

        acceptor = new Acceptor(child, configProvider);
    }

    @SneakyThrows
    public ListenableFutureTask<Void> bind(SocketAddress address) {
        ServerSocketChannel ssc = ServerSocketChannel.open(StandardProtocolFamily.INET);
        ssc.configureBlocking(false);

        var bindFuture = new ListenableFutureTask<Void>(null);

        parent.next().register(ssc, SelectionKey.OP_ACCEPT, acceptor)
                .addListener(new FutureListener<SelectionKey>() {
                    @SneakyThrows
                    @Override
                    public void operationComplete(ListenableFutureTask<SelectionKey> futureTask) {
                        ssc.bind(address);
                        log.debug("Listen on " + address);
                        bindFuture.run();
                    }
                });
        return bindFuture;
    }

    public void shutdown() {

    }

}
