package cf.wangyu1745.nettyRPC;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.concurrent.Promise;
import lombok.SneakyThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {


    private static final Object NULL = new Object();
    //    private static final int PORT = 8080;
    private final int port;
    private final Map<String, ServiceWrapper> m = new ConcurrentHashMap<>();
    private final ExecutorService exe = Executors.newCachedThreadPool();

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
//    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    private static class ServiceWrapper {
        Method[] methods;
        Object service;

        public ServiceWrapper(Class<?> c, Object service) {
            methods = c.getMethods();
            this.service = service;
        }
    }

    public Server(int port) {
        this.port = port;
    }

    public Server() {
        this.port = 8080;
    }

    @SneakyThrows
    public void start() {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 128).childOption(ChannelOption.TCP_NODELAY, true)
//             .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ObjectMapper mapper = new ObjectMapper();
                        ChannelPipeline p = ch.pipeline();
//                        p.addLast(new LoggingHandler(LogLevel.INFO));
                        p.addLast(new ReplayingDecoder<Client.Request>() {
                            @Override
                            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                                int len = in.readInt();
                                ByteBuf byteBuf = in.readBytes(len);
                                byte[] bytes = new byte[len];
                                byteBuf.readBytes(bytes);
                                Client.Request request = mapper.readValue(bytes, Client.Request.class);
                                out.add(request);
                                byteBuf.release();
                            }
                        });
                        p.addLast(new SimpleChannelInboundHandler<Client.Request>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Client.Request request) {
                                Promise<Object> promise = ctx.executor().newPromise();
                                ServiceWrapper serviceWrapper = m.get(request.clazz);
                                exe.submit(() -> {
                                    try {
                                        Object o = serviceWrapper.methods[request.methodIndex].invoke(serviceWrapper.service, request.args);
                                        promise.setSuccess(o);
                                    } catch (IllegalAccessException | InvocationTargetException e) {
                                        promise.setFailure(e);
                                    }
                                });
                                promise.addListener(future -> {
                                    if (future.isSuccess()) {
                                        ctx.channel().writeAndFlush(Optional.ofNullable(future.get()).orElse(NULL));
                                    } else {
                                        future.cause().printStackTrace();
                                        ctx.channel().writeAndFlush(NULL);
                                    }
                                });
                            }

                            /*@Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object[] msg) throws Exception {
//                                System.out.println("Server.channelRead0");
                                Promise<Object> promise = ctx.executor().newPromise();
                                ctx.executor().submit(() -> {
                                    promise.setSuccess("nmsl111");
                                });

                                promise.addListener((FutureListener<Object>) future -> {
                                    Object o = future.get();
                                    ctx.channel().writeAndFlush(o);
                                });
                            }*/
                        });
                        p.addLast(new MessageToByteEncoder<Object>() {
                            @Override
                            protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
                                if (msg == NULL) {
                                    ctx.writeAndFlush(ctx.alloc().buffer().writeInt(0));
                                } else {
                                    byte[] bytes = mapper.writeValueAsBytes(msg);
                                    ctx.writeAndFlush(ctx.alloc().buffer().writeInt(bytes.length).writeBytes(bytes));
                                }
                            }
                        });
                    }
                });

        // Start the server.
        serverBootstrap.bind(port).sync();
    }

    public void register(Class<?> c, Object o) {
        m.put(c.getName(), new ServiceWrapper(c, o));
    }
}
