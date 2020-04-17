package com.cszjo.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server implements Closeable {

    // 默认的Reader为2
    private static final int DEFAULT_READER_SIZE = 2;
    // 默认的Handler为4
    private static final int DEFAULT_HANDLER_SIZE = 4;

    private final BlockingQueue<Call> callQueue = new LinkedBlockingQueue<>();
    private final Handler[] handlers;
    private final int port;

    private Accepter accepter;

    public Server(int port) {
        this.port = port;
        this.handlers = new Handler[DEFAULT_HANDLER_SIZE];
    }

    public void start() {
        System.out.println("start Base64 Server...");
        // 先启动Accepter
        this.accepter = new Accepter(this.port, callQueue);
        new Thread(accepter).start();

        // 再启动Handler
        for (int i = 0; i < this.handlers.length; i++) {
            this.handlers[i] = new Handler(callQueue);
            new Thread(this.handlers[i]).start();
        }
    }

    private static class Accepter implements Runnable, Closeable {

        private final int port;
        private final Reader[] readers;
        private ServerSocketChannel serverSocketChannel;
        private volatile boolean running = true;
        private Selector selector;
        private int readerIndex = 0;

        public Accepter(int port, BlockingQueue<Call> callQueue) {
            this.port = port;

            System.out.println("start Accepter..");
            this.readers = new Reader[DEFAULT_READER_SIZE];
            for (int i = 0; i < this.readers.length; i++) {
                this.readers[i] = new Reader(callQueue);
                new Thread(this.readers[i]).start();
            }
        }

        @Override
        public void run() {
            // 绑定端口，并把serverSocketChannel的ACCEPT事件注册到Accepter的Select
            try {
                serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.bind(new InetSocketAddress(port));
                serverSocketChannel.configureBlocking(false);

                selector = Selector.open();

                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to open server socket channel");
            }

            while (running) {

                int acceptedCount;
                try {
                    acceptedCount = selector.select(100);
                } catch (IOException ignored) {
                    ignored.printStackTrace();
                    continue;
                }

                if (acceptedCount == 0) {

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        System.out.println("interrupt when sleep!");
                    }

                    continue;
                }

                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    if (!key.isAcceptable()) {
                        System.out.println("WARNING: get selection key isn't a " +
                                           "Acceptable key!");
                    }


                    try {
                        // 做accept事件
                        doAccept(key);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // close
            try {
                if (selector != null) {
                    selector.close();
                }

                if (serverSocketChannel != null) {
                    serverSocketChannel.close();
                }
            } catch (IOException ignored) {
                ignored.printStackTrace();
            }
        }

        private void doAccept(SelectionKey key) throws IOException {
            // 轮训选择一个Reader
            Reader reader = this.readers[readerIndex++ % DEFAULT_READER_SIZE];
            SocketChannel socketChannel = serverSocketChannel.accept();

            InetSocketAddress remoteAddress = (InetSocketAddress) socketChannel.getRemoteAddress();
            System.out.println(String.format("accept a connect from %s:%s",
                                             remoteAddress.getHostName(), remoteAddress.getPort()));
            // 将此Channel交给被选中的Reader
            reader.addChannel(socketChannel);
        }

        @Override
        public void close() {
            Arrays.stream(readers).forEach(Reader::close);
            running = false;
        }
    }

    private static class Reader implements Runnable, Closeable {

        private final BlockingQueue<Call> callQueue;

        private Selector selector;
        private volatile boolean running = true;


        public Reader(BlockingQueue<Call> callQueue) {
            System.out.println("start Reader..");
            this.callQueue = callQueue;
        }

        @Override
        public void run() {

            try {
                this.selector = Selector.open();
            } catch (IOException e) {
                System.out.println("Failed to open select in Reader");
                throw new RuntimeException("Failed to open select in Reader");
            }

            while (running) {

                int acceptedCount;
                try {
                    acceptedCount = selector.select(100);
                } catch (IOException ignored) {
                    ignored.printStackTrace();
                    continue;
                }

                if (acceptedCount == 0) {

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        System.out.println("interrupt when sleep!");
                    }

                    continue;
                }

                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isReadable()) {
                        continue;
                    }

                    doRead(key);
                }
            }

            try {
                if (selector != null) {
                    selector.close();
                }
            } catch (IOException ignored) {
                ignored.printStackTrace();
            }
        }

        private void addChannel(SocketChannel channel) {
            try {
                System.out.println("add reading channels..");
                // 因为有可能Select会在轮询中block，所以wakeUp是有必要的
                selector.wakeup();
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ);
            } catch (IOException ignored) {
                ignored.printStackTrace();
            }
        }

        private void doRead(SelectionKey key) {
            SocketChannel channel = (SocketChannel) key.channel();

            byte[] dataBytes;
            try {
                // 从Channel中读取数据，并将Call推到阻塞队列中
                ByteBuffer dataLengthBuf = ByteBuffer.allocate(4);
                channel.read(dataLengthBuf);
                dataLengthBuf.flip();
                int dataLength = dataLengthBuf.getInt();

                ByteBuffer dataBuf = ByteBuffer.allocate(dataLength);
                channel.read(dataBuf);
                dataBuf.flip();
                dataBytes = dataBuf.array();
                System.out.println("accept a msg, length = " + dataLength +
                                   ", content = " + new String(dataBytes));
            } catch (IOException ignored) {
                ignored.printStackTrace();
                return;
            }
            callQueue.offer(new Call(channel, dataBytes));
        }

        @Override
        public void close() {
            running = false;
        }
    }

    static final class Call {
        final SocketChannel channel;
        final byte[] dataBytes;

        public Call(SocketChannel channel, byte[] dataBytes) {
            this.channel = channel;
            this.dataBytes = dataBytes;
        }
    }

    private static class Handler implements Runnable, Closeable {

        private final BlockingQueue<Call> callQueue;

        private volatile boolean running = true;


        public Handler(BlockingQueue<Call> callQueue) {
            System.out.println("start Handler..");
            this.callQueue = callQueue;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Call call = callQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (call == null) {
                        continue;
                    }
                    // Handler处理Call，对传过来的数据做Base64加密，其实在这里实现不同的方法
                    // 一个简单的rpc调用就可以实现了
                    byte[] encode = Base64.getEncoder().encode(call.dataBytes);
                    ByteBuffer resBuf = ByteBuffer.allocate(encode.length + 4);
                    resBuf.putInt(encode.length);
                    resBuf.put(encode);

                    System.out.println("response a msg, length = " + encode.length +
                                       ", content = " + new String(encode));
                    resBuf.flip();
                    call.channel.write(resBuf);
                } catch (InterruptedException | IOException ignored) {
                    ignored.printStackTrace();
                }
            }
        }

        @Override
        public void close() {
            running = false;
        }
    }

    @Override
    public void close() {
        Arrays.stream(handlers).forEach(Handler::close);
        accepter.close();
    }
}
