package io.github.light0x00.letty.core.handler;

import io.github.light0x00.letty.core.AbstractNioSocketChannel;
import io.github.light0x00.letty.core.LettyConfiguration;
import io.github.light0x00.letty.core.LettyProperties;
import io.github.light0x00.letty.core.buffer.BufferPool;
import io.github.light0x00.letty.core.buffer.RecyclableBuffer;
import io.github.light0x00.letty.core.buffer.RingBuffer;
import io.github.light0x00.letty.core.concurrent.ListenableFutureTask;
import io.github.light0x00.letty.core.eventloop.EventExecutor;
import io.github.light0x00.letty.core.eventloop.EventExecutorGroup;
import io.github.light0x00.letty.core.eventloop.NioEventLoop;
import io.github.light0x00.letty.core.facade.InitializingSocketChannel;
import io.github.light0x00.letty.core.handler.adapter.DuplexChannelHandler;
import io.github.light0x00.letty.core.util.LettyException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 基于责任链和观察者模式分发底层事件
 * <p>
 * handle the nio event and dispatch them to {@link DuplexChannelHandler}
 *
 * @author light0x00
 * @since 2023/6/29
 */
@Slf4j
public class SocketChannelEventHandler implements NioEventHandler {

    protected final NioEventLoop eventLoop;

    protected final SelectionKey key;

    protected final ChannelContext context;

    protected final BufferPool bufferPool;

    protected final LettyProperties lettyConf;

    protected final SocketChannel javaChannel;

    @Getter
    protected final NioSocketChannel channel;

    private final OutputBuffer outputBuffer = new OutputBuffer();

    /**
     * 对方是否发送 FIN 包, 这意味着已经读完了 socket 读缓冲区的最后一个字节
     *
     * @implNote Be aware that should only be access by current event loop thread,
     * cannot be shared between threads.
     */
    private boolean outputClosed;

    /**
     * 当前端是否发送了 FIN 包, 这意味着不会再写入更多数据到 socket 写缓冲区
     *
     * @implNote Be aware that should only be access by current event loop thread,
     * cannot be shared between threads.
     */
    private boolean inputClosed;

    /**
     * 用于执行责任链和观察者
     */
    protected final EventExecutor handlerExecutor;

    final ChannelHandlerDispatcher dispatcher;

    /**
     * 用于反馈握手的结果,成功或失败
     */
    @Getter
    protected final ListenableFutureTask<NioSocketChannel> connectableFuture;

    public SocketChannelEventHandler(NioEventLoop eventLoop,
                                     SocketChannel channel,
                                     SelectionKey key,
                                     LettyConfiguration lettyConfiguration) {
        this(eventLoop, channel, key, lettyConfiguration, new ListenableFutureTask<>(null));
    }

    public SocketChannelEventHandler(NioEventLoop eventLoop,
                                     SocketChannel javaChannel,
                                     SelectionKey key,
                                     LettyConfiguration configuration,
                                     ListenableFutureTask<NioSocketChannel> connectableFuture
    ) {
        this.eventLoop = eventLoop;
        this.javaChannel = javaChannel;
        this.key = key;
        this.connectableFuture = connectableFuture;
        channel = new NioSocketChannelImpl();
        context = buildContext();
        lettyConf = configuration.lettyProperties();
        bufferPool = configuration.bufferPool();

        var channelConfiguration = new InitializingSocketChannel(javaChannel, eventLoop);

        configuration.channelInitializer()
                .initChannel(channelConfiguration);

        //determine executor
        EventExecutorGroup<?> handlerExecutorGroup = channelConfiguration.executorGroup();
        if (handlerExecutorGroup == null || eventLoop.group() == handlerExecutorGroup) {
            //如果没有单独指定 executor 去执行用户代码, 那么使用当前 event loop 执行.
            handlerExecutor = eventLoop;
        } else {
            handlerExecutor = handlerExecutorGroup.next();
        }

        dispatcher = new ChannelHandlerDispatcher(
                handlerExecutor,
                context,
                channelConfiguration.handlerExecutorPair(),
                (data) -> {
                    log.warn("Discarded inbound message {} that reached at the tail of the pipeline. Please check your pipeline configuration.", data);
                },
                (data, future) -> {
                    if (eventLoop.inEventLoop()) {
                        write(data, future);
                    } else {
                        eventLoop.submit(() -> write(data, future));
                    }
                }
        );
    }

    @Override
    public void onEvent(SelectionKey key) throws IOException {
        if (key.isReadable()) {
            processReadableEvent();
        } else if (key.isWritable()) {
            processWritableEvent();
        } else if (key.isConnectable()) {
            processConnectableEvent();
        }
    }

    private void processConnectableEvent() throws IOException {
        try {
            javaChannel.finishConnect();
        } catch (IOException e) {
            connectableFuture.setFailure(e);
            close();
            return;
        }
        connectableFuture.setSuccess(channel);
        dispatcher.onConnected();
        key.interestOps((key.interestOps() ^ SelectionKey.OP_CONNECT) | SelectionKey.OP_READ);
    }

    private void processReadableEvent() throws IOException {
        int n;
        do {
            RecyclableBuffer buf = bufferPool.take(lettyConf.readBufSize());
            n = buf.readFromChannel(javaChannel);
            dispatcher.input(buf);
        } while (n > 0);
        if (n == -1) {
            log.debug("Received FIN from {}", javaChannel.getRemoteAddress());

            readCompleted();
        }
    }

    private void processWritableEvent() throws IOException {
        for (WriterFuturePair bufFuture; (bufFuture = outputBuffer.peek()) != null; ) {

            WriteStrategy writer = bufFuture.writer();
            /*
             * Some types of channels, depending upon their state,
             * may write only some of the bytes or possibly none at all.
             * A socket channel in non-blocking mode, for example,
             * cannot write any more bytes than are free in the socket's output buffer.
             */
            writer.write(javaChannel);
            if (writer.remaining() == 0) {
                outputBuffer.poll();
                bufFuture.future.setSuccess();
                if (writer.getSource() instanceof RecyclableBuffer recyclable) {
                    recyclable.release();
                    log.debug("Recycle buffer {}", recyclable);
                }
            } else {
                //如果还有剩余，意味 socket 发送缓冲着已经满了，只能等待下一次 Writable 事件
                log.debug("Underlying socket sending buffer is full, wait next time writable event.");
                return;
            }
        }

        key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
        log.debug("All the outbound buffers flushed, remove interest to writeable event.");
    }

    /**
     * Race condition with:
     * <li> {@link #shutdownOutput()} , if then act race condition on the mutable state {@link #outputClosed}
     *
     * <p>
     * Be aware that must be executed in curren event loop, cuz there is no any synchronization!
     */
    @SneakyThrows
    private ListenableFutureTask<Void> write(Object data, ListenableFutureTask<Void> writeFuture) {
        if (outputClosed) {
            writeFuture.setFailure(new LettyException("Channel output has been shut down"));
            return writeFuture;
        }

        WriteStrategy rBuf;
        if (data instanceof RingBuffer) {
            rBuf = new RingBufferWriteStrategy((RingBuffer) data);
        } else if (data instanceof ByteBuffer bBuf) {
            rBuf = new ByteBufferWriteStrategy(bBuf);
        } else if (data.getClass().equals(byte[].class)) {
            byte[] bBuf = (byte[]) data;
            rBuf = new ByteBufferWriteStrategy(bBuf);
        } else if (data instanceof FileChannel fc) {
            rBuf = new FileChannelWriteStrategy(fc);
        } else {
            writeFuture.setFailure(new LettyException("Unsupported data type to write:" + data.getClass()));
            return writeFuture;
        }

        if (outputBuffer.isEmpty()) {
            rBuf.write(javaChannel);
        }
        if (rBuf.remaining() > 0) {
            outputBuffer.offer(new WriterFuturePair(rBuf, writeFuture));
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        } else {
            if (rBuf.getSource() instanceof RecyclableBuffer recyclable) {
                recyclable.release();
                log.debug("Recycle buffer {}", recyclable);
            }
        }
        return writeFuture;
    }

    private void readCompleted() {
        //移除事件监听
        key.interestOps(key.interestOps() ^ SelectionKey.OP_READ); //remove read from interestOps
        //更新状态
        inputClosed = true;
        //事件通知
        dispatcher.onReadCompleted();

        if (outputClosed) {
            onFinalized();
        } else if (!lettyConf.isAllowHalfClosure()) {
            shutdownOutput();
        }
    }

    @SneakyThrows
    private void shutdownInput() {
        //这个调用会触发一个读事件, read 返回 -1
        //此调用之后对方发送的数据都会收到 RST
        javaChannel.shutdownInput();
    }

    /**
     * Race condition with:
     *
     * <li>{@link #close()} , may concurrently change the share mutable state {@link #outputClosed}
     * <li>{@link #processWritableEvent()} , cannot concurrently execute, may cause a {@link java.nio.channels.CancelledKeyException}
     * <li>{@link #write(Object, ListenableFutureTask)} , if then act race condition on the mutable state {@link #outputClosed}
     *
     * <p>
     * Be aware that must be executed in curren event loop, cuz there is no any synchronization!
     */
    @SneakyThrows
    private void shutdownOutput() {
        //幂等
        if (outputClosed)
            return;
        outputClosed = true;
        //清除输出缓冲
        outputBuffer.invalid();
        //移除监听
        key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
        //关闭底层 socket 的输出
        javaChannel.shutdownOutput();
        log.debug("Send FIN to {}", javaChannel.getRemoteAddress());

        if (inputClosed) {
            onFinalized();
        }
    }

    /**
     * Race condition with:
     *
     * <li> {@link #readCompleted()} , race condition on the mutable state {@link #inputClosed}
     * <li> {@link #shutdownOutput()} , race condition on the mutable state {@link #outputClosed}
     *
     * <p>
     * Be aware that must be executed in curren event loop, cuz there is no any synchronization!
     */
    public void close() {

        //幂等
        if (outputClosed && inputClosed) {
            return;
        }
        //close 的时候有可能已经调用了 shutdownOutput , 也可能没有
        //如果没有, 则需要执行一次清空
        if (!outputClosed) {
            outputBuffer.invalid();
        }
        outputClosed = inputClosed = true;

        onFinalized();
    }

    @SneakyThrows
    private void onFinalized() {
        String name = javaChannel.toString();

        javaChannel.close();
        key.cancel();

        log.debug("Release resource associated with channel {}", name);

        dispatcher.onClosed();
    }

    class NioSocketChannelImpl extends AbstractNioSocketChannel {

        public NioSocketChannelImpl() {
            super(javaChannel);
        }

        @Nonnull
        @Override
        public ListenableFutureTask<Void> connectedFuture() {
            return SocketChannelEventHandler.this.dispatcher.connectedFuture();
        }

        @Nonnull
        @Override
        public ListenableFutureTask<Void> closeFuture() {
            return SocketChannelEventHandler.this.dispatcher.closedFuture();
        }

        @Nonnull
        @Override
        public ListenableFutureTask<Void> shutdownInput() {
            return eventLoop.submit(SocketChannelEventHandler.this::shutdownInput);
        }

        @Nonnull
        @Override
        public ListenableFutureTask<Void> shutdownOutput() {
            return eventLoop.submit(SocketChannelEventHandler.this::shutdownOutput);
        }

        @Nonnull
        @Override
        public ListenableFutureTask<Void> close() {
            eventLoop.execute(SocketChannelEventHandler.this::close);
            return closeFuture();
        }

        @Nonnull
        @Override
        public ListenableFutureTask<Void> write(@Nonnull Object data) {
            return dispatcher.output(data);
        }
    }

    @NotNull
    private ChannelContext buildContext() {
        return new ChannelContext() {

            @NotNull
            @Override
            public NioSocketChannel channel() {
                return channel;
            }

            @NotNull
            @Override
            public RecyclableBuffer allocateBuffer(int capacity) {
                return bufferPool.take(capacity);
            }
        };
    }

    private record WriterFuturePair(WriteStrategy writer, ListenableFutureTask<Void> future) {

    }

    public static class OutputBuffer {
        private final Queue<WriterFuturePair> outputBuffer = new ConcurrentLinkedDeque<>();

        public void offer(WriterFuturePair bf) {
            outputBuffer.offer(bf);
        }

        public WriterFuturePair poll() {
            return outputBuffer.poll();
        }

        public WriterFuturePair peek() {
            return outputBuffer.peek();
        }

        public boolean isEmpty() {
            return outputBuffer.isEmpty();
        }

        public void invalid() {
            log.debug("Clear outbound buffer ,remaining:{}", outputBuffer.size());
            for (WriterFuturePair bufFuture; (bufFuture = outputBuffer.poll()) != null; ) {
                bufFuture.future.setFailure(new LettyException("Output Buffer cleared"));
            }
        }
    }

    public interface WriteStrategy {
        long write(GatheringByteChannel channel) throws IOException;

        long remaining();

        Object getSource();
    }

    @AllArgsConstructor
    static class RingBufferWriteStrategy implements WriteStrategy {

        private RingBuffer ringBuffer;

        @Override
        public long write(GatheringByteChannel channel) throws IOException {
            return ringBuffer.writeToChannel(channel);
        }

        @Override
        public long remaining() {
            return ringBuffer.remainingCanGet();
        }

        @Override
        public Object getSource() {
            return ringBuffer;
        }
    }

    @AllArgsConstructor
    static class FileChannelWriteStrategy implements WriteStrategy {
        private FileChannel fileChannel;
        private long position;

        public FileChannelWriteStrategy(FileChannel fileChannel) {
            this.fileChannel = fileChannel;
        }

        @Override
        public long write(GatheringByteChannel channel) throws IOException {
            long written = fileChannel.transferTo(position, fileChannel.size() - position, channel);
            position += written;
            return written;
        }

        @SneakyThrows
        @Override
        public long remaining() {
            return fileChannel.size() - position;
        }

        @Override
        public Object getSource() {
            return fileChannel;
        }
    }

    static class ByteBufferWriteStrategy implements WriteStrategy {

        private final ByteBuffer buffer;

        public ByteBufferWriteStrategy(byte[] arr) {
            this.buffer = ByteBuffer.wrap(arr);
        }

        public ByteBufferWriteStrategy(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public long write(GatheringByteChannel channel) throws IOException {
            return channel.write(buffer);
        }

        @Override
        public long remaining() {
            return buffer.remaining();
        }

        @Override
        public Object getSource() {
            return buffer;
        }
    }

}
