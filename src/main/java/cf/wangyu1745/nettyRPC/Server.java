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

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
    private final Map<String, ServiceWrapper> serviceWrapperMap = new ConcurrentHashMap<>();
    private final ExecutorService exe = Executors.newCachedThreadPool();

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
//    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    private static class ServiceWrapper {
        final Method[] methods;
        final Object service;

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
                                int reqLen = in.readInt();
                                ByteBuf reqBuf = in.readBytes(reqLen);
                                byte[] reqBytes = new byte[reqLen];
                                reqBuf.readBytes(reqBytes);
                                reqBuf.release();
                                int argsLen = in.readInt();
                                if (argsLen == 0) {
                                    Client.Request request = mapper.readValue(reqBytes, Client.Request.class);
                                    out.add(request);
                                    return;
                                }
                                ByteBuf argsBuf = in.readBytes(argsLen);
                                byte[] argsBytes = new byte[argsLen];
                                argsBuf.readBytes(argsBytes);
                                argsBuf.release();
                                Client.Request request = mapper.readValue(reqBytes, Client.Request.class);
                                ServiceWrapper serviceWrapper = serviceWrapperMap.get(request.clazz);
                                Method method = serviceWrapper.methods[request.methodIndex];
                                Class<?>[] parameterTypes = method.getParameterTypes();
                                ByteArrayInputStream inputStream = new ByteArrayInputStream(argsBytes);
                                List<Object> args = new ArrayList<>();
                                for (Class<?> type : parameterTypes) {
                                    args.add(mapper.readValue(inputStream, type));
                                }
                                request.args = args.toArray();
                                out.add(request);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                System.out.println("Server.exceptionCaught");
                                cause.printStackTrace();
                                ctx.channel().close();
                            }
                        });
                        p.addLast(new SimpleChannelInboundHandler<Client.Request>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Client.Request request) {
                                Promise<Object> promise = ctx.executor().newPromise();
                                ServiceWrapper serviceWrapper = serviceWrapperMap.get(request.clazz);
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
        serviceWrapperMap.put(c.getName(), new ServiceWrapper(c, o));
    }
}
