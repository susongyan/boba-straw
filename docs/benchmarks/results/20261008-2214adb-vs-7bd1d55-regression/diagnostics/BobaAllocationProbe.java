import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.BobaStrawPipeline;
import java.lang.management.ManagementFactory;

/** Diagnostic only: caller-thread allocation for constructing, NOT executing, raw Pipeline. */
public final class BobaAllocationProbe {
    private static volatile Object sink;

    private static void build(BobaStrawClient client, int batches) {
        for (int i = 0; i < batches; i++) {
            BobaStrawPipeline pipeline = client.pipeline();
            for (int j = 0; j < 128; j++) {
                pipeline.command("GET", "boba:benchmark:allocation-probe");
            }
            sink = pipeline;
        }
    }

    public static void main(String[] args) throws Exception {
        com.sun.management.ThreadMXBean bean =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation measurement unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        try (BobaStrawClient client = BobaStrawClient.builder()
            .uri("redis://127.0.0.1:17379").build()) {
            if (!"PONG".equals(client.sync().ping())) {
                throw new IllegalStateException("Fixture unavailable");
            }
            build(client, 20000);
            for (int sample = 0; sample < 5; sample++) {
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                build(client, 20000);
                long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
                System.out.println("sample=" + sample + " bytesPerQueuedCommand="
                    + (allocated / (20000.0 * 128)));
            }
        }
    }
}
