package cf.wangyu1745.nettyRPC;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Client {
    private final String host;
    private final int port;
    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    //rpc调用返回为null时的标记对象
    private static final Object NULL = new Object();
    private final ExecutorService exe = Executors.newCachedThreadPool();
    private final Map<Channel, Request> requestMap = new ConcurrentHashMap<>();

    private final Deque<InvokeHandler.Connection> deque = new ConcurrentLinkedDeque<>();


    private class InvokeHandler implements InvocationHandler {
        @AllArgsConstructor
        private class Connection {
            final Channel channel;
            final Condition condition;
        }

        //连接池
        //method映射到int
        private final Map<Method, Integer> methodIntegerMap = new HashMap<>();
        final ReentrantLock lock = new ReentrantLock();

        public InvokeHandler(Class<?> c) {
            final int[] i = {0};
            Arrays.stream(c.getMethods()).forEach(e -> methodIntegerMap.put(e, i[0]++));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                lock.lock();
                Connection poll = deque.poll();
                if (poll == null) {
                    //建立新连接
                    Condition condition = lock.newCondition();
                    Request request = new Request(args, method.getDeclaringClass().getName(), methodIntegerMap.get(method), method, condition, lock);
                    newConnection().addListener((ChannelFutureListener) future -> {
                        Channel channel = future.channel();
                        try {
                            lock.lock();
                            request.channel = channel;
                        } finally {
                            lock.unlock();
                        }
                        channel.writeAndFlush(request);
                    });
                    //防止永久阻塞
                    if (!condition.await(3, TimeUnit.SECONDS)) {
                        // 超时
                        System.out.println(request + " 超时");
                        if (request.channel != null) {
                            //已经建立了连接，需要销毁连接和request
                            requestMap.remove(request.channel);
                            request.channel.close();
                        }
                    } else {
                        // 不超时
                        if (request.channel != null) {
                            deque.offer(new Connection(request.channel, condition));
                        }
                    }
                    return request.rt;
                } else {
                    //复用连接
                    Request request = new Request(args, method.getDeclaringClass().getName(), methodIntegerMap.get(method), method, poll.condition, lock);
                    poll.channel.writeAndFlush(request);
                    //防止永久阻塞
                    if (!poll.condition.await(3, TimeUnit.SECONDS)) {
                        // 超时
                        System.out.println(request + " 超时");
                        if (request.channel != null) {
                            //已经建立了连接，需要销毁连接和request
                            requestMap.remove(request.channel);
                            request.channel.close();
                        }
                    } else {
                        // 不超时
                        deque.offer(poll);
                    }
                    return request.rt;
                }
            } finally {
                lock.unlock();
            }
        }
    }


    @Getter
    @NoArgsConstructor
    @ToString
    protected static class Request {
        public Request(Object[] args, String clazz, int methodIndex, Method method, Condition condition, ReentrantLock lock) {
            this.args = args;
            this.clazz = clazz;
            this.methodIndex = methodIndex;
            this.method = method;
            this.condition = condition;
            this.lock = lock;
        }

        @JsonIgnore
        Object[] args;
        String clazz;
        int methodIndex;
        @JsonIgnore
        Method method;
        @JsonIgnore
        Condition condition;
        @JsonIgnore
        ReentrantLock lock;

        @JsonIgnore
        @Setter
        Object rt;

        @JsonIgnore
        @Setter
        Channel channel;
    }

    public Client(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public Client() {
        this.host = "localhost";
        this.port = 8080;
    }

    private ChannelFuture newConnection() {
        Bootstrap b = new Bootstrap();
        ObjectMapper mapper = new ObjectMapper();
        b.group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) {
                ChannelPipeline p = ch.pipeline();
                p.addLast(new MessageToByteEncoder<Request>() {
                    @Override
                    protected void encode(ChannelHandlerContext ctx, Request request, ByteBuf out) throws Exception {
                        requestMap.put(ctx.channel(), request);
                        byte[] reqBytes = mapper.writeValueAsBytes(request);
                        out.writeInt(reqBytes.length).writeBytes(reqBytes);
                        ArrayList<byte[]> args = new ArrayList<>();
                        if (request.args != null) {
                            for (Object arg : request.args) {
                                args.add(mapper.writeValueAsBytes(arg));
                            }
                            Integer len = args.stream().map(e -> e.length).reduce(0, Integer::sum);
                            out.writeInt(len);
                            args.forEach(out::writeBytes);
                        } else {
                            out.writeInt(0);
                        }
                    }
                });
                p.addLast(new ReplayingDecoder<Object>() {
                    @Override
                    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                        Request request = requestMap.get(ctx.channel());
                        int len = in.readInt();
                        if (len == 0) {
                            out.add(NULL);
                            return;
                        }
                        ByteBuf byteBuf = in.readBytes(len);
                        byte[] bytes = new byte[len];
                        byteBuf.readBytes(bytes);
                        Object object = mapper.readValue(bytes, request.method.getReturnType());
                        out.add(object);
                        byteBuf.release();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//                        cause.printStackTrace();
                        ctx.channel().close();
                        Request request = requestMap.remove(ctx.channel());
                        request.lock.lock();
                        request.condition.signal();
                        request.lock.unlock();
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        ctx.channel().close();
                        Request request = requestMap.remove(ctx.channel());
                        if (request != null) {
                            request.lock.lock();
                            request.condition.signal();
                            request.lock.unlock();
                        }
                    }
                });
                p.addLast(new SimpleChannelInboundHandler<Object>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                        Request request = requestMap.remove(ctx.channel());
                        exe.submit(() -> {
//                            try {
                            request.lock.lock();
                            if (msg == NULL) {
                                request.setRt(null);
                            } else {
                                request.setRt(msg);
                            }
                            request.condition.signal();
//                            } finally {
                            request.lock.unlock();
//                            }
                        });
                    }
                });
            }
        });
        return b.connect(host, port);
    }

    @SuppressWarnings("unchecked")
    @SneakyThrows
    public <T> T getService(Class<T> c) {
        return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class[]{c}, new InvokeHandler(c));
    }
}
