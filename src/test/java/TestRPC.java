import cf.wangyu1745.nettyRPC.Client;
import cf.wangyu1745.nettyRPC.Server;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TestRPC {

    @SuppressWarnings("UnusedReturnValue")
    public interface ITest {
        String test(String s);

        String null_(String s);

        int identify(int a);

        void A(A a);
    }


    public static class ITestImpl implements ITest {
//        private final AtomicInteger i = new AtomicInteger(0);

        @Override
        public String test(String s) {
            return "nmsl " + s;
        }

        @Override
        public String null_(String s) {
            return null;
        }

        @Override
        public int identify(int a) {
            return a;
        }

        @Override
        public void A(A a) {
            System.out.println(a.toString());
        }


    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class A {
        String a;
        int b;
        byte[] c;
    }


    @Test
    public void qps() throws InterruptedException {
        Client client = new Client();
        ITest ITest = client.getService(ITest.class);
        AtomicInteger integer = new AtomicInteger();
        for (int i = 0; i < 1/*Runtime.getRuntime().availableProcessors()*/; i++) {
            new Thread(() -> {
                for (int j = 0; j < Integer.MAX_VALUE; j++) {
                    ITest.test("");
                    integer.incrementAndGet();
                }
            }).start();
        }
        new Thread(() -> {
            while (true) {
                try {
                    int i = integer.get();
                    TimeUnit.SECONDS.sleep(1);
                    System.out.println(integer.get() - i);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
        new CountDownLatch(1).await();
    }


    @Test
    public void sync() throws InterruptedException {
        Client client = new Client();
        ITest ITest = client.getService(ITest.class);
        new Thread(() -> {
            for (int i = 0; i < Integer.MAX_VALUE / 2; i++) {
                Assertions.assertEquals(i, ITest.identify(i));
            }
        }).start();
        new Thread(() -> {
            for (int i = Integer.MAX_VALUE / 2; i < Integer.MAX_VALUE; i++) {
                Assertions.assertEquals(i, ITest.identify(i));
            }
        }).start();
        new CountDownLatch(1).await();
    }

    @Test
    public void bootTime() throws InterruptedException {
        Client client = new Client();
        ITest ITest = client.getService(ITest.class);
        int num = 40000;
        List<Double> list = new Vector<>(num);
        ExecutorService service = Executors.newCachedThreadPool();
        @SuppressWarnings("unchecked") CompletableFuture<Void>[] futures = new CompletableFuture[num];
        long l = System.nanoTime();
        for (int i = 0; i < num; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                ITest.test("");
                list.add((double) (System.nanoTime() - l) / 1000000);
            }, service);
        }
        CompletableFuture.allOf(futures).join();
        Collections.sort(list);
        System.out.println(list.get(list.size() - 1) - list.get(0));
        new CountDownLatch(1).await();
    }

    @Test
    public void args() {
        ITest service = new Client().getService(ITest.class);
        service.A(new A("dass", 1, new byte[]{1, 2, 34, 5}));
    }

    @BeforeAll
    public static void server() {
        Server server = new Server();
        server.register(ITest.class, new ITestImpl());
        server.start();
    }

    @Test
    public void testNull() {
        ITest service = new Client().getService(ITest.class);
        Assertions.assertNull(service.null_(""));
    }


    /*@Test
    public void a() throws JsonProcessingException {
        @Data
        class A {
            String a = "nmsl";
            @JsonIgnore
            Condition condition;
        }
        ObjectMapper mapper = new ObjectMapper();
        System.out.println(mapper.writeValueAsString(new A()));
    }

    @Test
    public void argsName() throws NoSuchMethodException {
        class A {
            public void a(String s, int i, double[] doubles) {
            }
        }
        Method method = A.class.getMethod("a", String.class, int.class, double[].class);
        String[] argsName = Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toArray(String[]::new);
        Arrays.stream(argsName).forEach(System.out::println);
    }

    @Test
    public void methodEqual() {
        class A implements nmsl {
            @Override
            public String nmsl_(String s) {
                return null;
            }
        }
        nmsl nmsl_ = (nmsl) Proxy.newProxyInstance(A.class.getClassLoader(), new Class[]{nmsl.class}, ((proxy, method, args) -> {
            System.out.println(method.equals(nmsl.class.getMethod("nmsl_", String.class)));
            return null;
        }));
        nmsl_.nmsl_("");
    }

    @Test
    public void port() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 100).childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.close();
            }
        });

        // Start the server.
        ChannelFuture f = b.bind(0).sync();
        System.out.println(f.channel().localAddress().toString());
    }

    @Test
    public void tryFinally() {
        try {
            System.out.println("1");
            throw new RuntimeException();
        } finally {
            System.out.println("2");
        }
    }

    @Test
    public void toString_() {
        Arrays.stream(this.getClass().getMethods()).map(Method::getName).forEach(System.out::println);
    }*/


}
