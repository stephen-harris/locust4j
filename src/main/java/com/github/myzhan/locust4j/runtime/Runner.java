package com.github.myzhan.locust4j.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.myzhan.locust4j.AbstractTask;
import com.github.myzhan.locust4j.Locust;
import com.github.myzhan.locust4j.message.Message;
import com.github.myzhan.locust4j.rpc.Client;
import com.github.myzhan.locust4j.stats.Stats;
import com.github.myzhan.locust4j.utils.Utils;
import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Runner} is a state machine that tells to the master, runs all tasks, collects test results
 * and reports to the master.
 *
 * @author myzhan
 */
public class Runner {

    private static final Logger logger = LoggerFactory.getLogger(Runner.class);

    /**
     * Every locust4j instance registers a unique nodeID to the master when it makes a connection.
     * NodeID is kept by Runner.
     */
    protected String nodeID;

    /**
     * Number of clients required by the master, locust4j use threads to simulate clients.
     */
    protected int numClients = 0;

    /**
     * Current state of runner.
     */
    private RunnerState state;

    /**
     * Task instances submitted by user.
     */
    private List<AbstractTask> tasks;

    /**
     * RPC Client.
     */
    private Client rpcClient;

    /**
     * We save user_class_count in spawn message and send it back to master without modification.
     */
    private Map<String, Integer> userClassesCountFromMaster;

    /**
     * Remote params sent from the master, which is set before spawning begins.
     */
    private final Map<String, Object> remoteParams = new ConcurrentHashMap<>();

    /**
     * Thread pool used by runner, it will be re-created when runner starts spawning.
     */
    private ExecutorService taskExecutor;

    /**
     * Thread pool used by runner to receive and send message
     */
    private ExecutorService executor;

    /**
     * Stats collect successes and failures.
     */
    private Stats stats;

    /**
     * Use this for naming threads in the thread pool.
     */
    private final AtomicInteger threadNumber = new AtomicInteger();

    /**
     * Disable heartbeat request.
     */
    private final AtomicBoolean heartbeatStopped = new AtomicBoolean(false);

    protected void setHeartbeatStopped(boolean value) {
        heartbeatStopped.set(value);
    }

    protected boolean isHeartbeatStopped() {
        return heartbeatStopped.get();
    }

    public Runner() {
        this.nodeID = Utils.getNodeID();
    }

    public RunnerState getState() {
        return this.state;
    }

    public String getNodeID() {
        return this.nodeID;
    }

    public void setRPCClient(Client client) {
        this.rpcClient = client;
    }

    public Map<String, Object> getRemoteParams() {
        return this.remoteParams;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public void setTasks(List<AbstractTask> tasks) {
        this.tasks = tasks;
    }

    private void spawnWorkers(int spawnCount) {
        logger.debug("Spawning {} clients", spawnCount);

        float weightSum = 0;
        for (AbstractTask task : this.tasks) {
            weightSum += task.getWeight();
        }

        for (AbstractTask task : this.tasks) {
            int amount;
            if (0 == weightSum) {
                amount = spawnCount / this.tasks.size();
            } else {
                float percent = task.getWeight() / weightSum;
                amount = Math.round(spawnCount * percent);
            }

            logger.debug("Allocating {} threads to task, which name is {}", amount, task.getName());

            for (int i = 1; i <= amount; i++) {
                this.numClients++;
                this.taskExecutor.submit(task);
            }
        }
    }

    protected void startSpawning(int spawnCount) {
        stats.getClearStatsQueue().offer(true);
        Stats.getInstance().wakeMeUp();

        this.numClients = 0;
        this.threadNumber.set(0);
        this.taskExecutor = new ThreadPoolExecutor(spawnCount, spawnCount, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("locust4j-worker#" + threadNumber.getAndIncrement());
                    return thread;
                }
            });
        this.spawnWorkers(spawnCount);
    }

    protected void spawnComplete() {
        Map<String, Object> data = new HashMap<>(1);
        data.put("count", this.numClients);
        data.put("user_classes_count", this.userClassesCountFromMaster);
        try {
            this.rpcClient.send((new Message("spawning_complete", data, null, this.nodeID)));
        } catch (IOException ex) {
            logger.error("Error while sending a message about the completed spawn", ex);
        }
    }

    public void quit() {
        try {
            this.rpcClient.send(new Message("quit", null, null, this.nodeID));
            this.executor.shutdownNow();
        } catch (IOException ex) {
            logger.error("Error while sending a message about quiting", ex);
        }
    }

    private void shutdownThreadPool() {
        this.taskExecutor.shutdownNow();
        try {
            this.taskExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            logger.error("Error while waiting for termination", ex);
        }
        this.taskExecutor = null;
    }

    protected void stop() {
        this.shutdownThreadPool();
    }

    private boolean spawnMessageIsValid(Message message) {
        Map<String, Object> data = message.getData();
        if (!data.containsKey("user_classes_count")) {
            logger.debug("Invalid spawn message without user_classes_count, you may use a newer but incompatible version of locust.");
            return false;
        }
        return true;
    }

    private int sumUsersAmount(Message message) {
        Map<String, Integer> userClassesCount = (Map<String, Integer>)message.getData().get("user_classes_count");
        int amount = 0;
        for (Map.Entry<String, Integer> entry: userClassesCount.entrySet()) {
            amount = amount + entry.getValue();
        }
        this.userClassesCountFromMaster = userClassesCount;
        return amount;
    }

    private void onSpawnMessage(Message message) {
        Map<String, Object> data = message.getData();
        int numUsers = sumUsersAmount(message);

        try {
            this.rpcClient.send(new Message("spawning", null, null, this.nodeID));
        } catch (IOException ex) {
            logger.error("Error while sending a message about spawning", ex);
        }

        this.remoteParams.put("user_classes_count", this.userClassesCountFromMaster);
        if (data.get("host") != null) {
            this.remoteParams.put("host", data.get("host").toString());
        }

        this.startSpawning(numUsers);
        this.spawnComplete();
    }

    private void onMessage(Message message) {
        String type = message.getType();

        if (!"spawn".equals(type) && !"stop".equals(type) && !"quit".equals(type)) {
            logger.error("Got {} message from master, which is not supported, please report an issue to locust4j.", type);
            return;
        }

        if ("quit".equals(type)) {
            logger.debug("Got quit message from master, shutting down...");
            System.exit(0);
        }

        if (this.state == RunnerState.Ready) {
            if ("spawn".equals(type) && spawnMessageIsValid(message)) {
                this.state = RunnerState.Spawning;
                this.onSpawnMessage(message);

                if (null != Locust.getInstance().getRateLimiter()) {
                    Locust.getInstance().getRateLimiter().start();
                }

                this.state = RunnerState.Running;
            }
        } else if (this.state == RunnerState.Spawning || this.state == RunnerState.Running) {
            if ("spawn".equals(type) && spawnMessageIsValid(message)) {
                // TODO: Since locust 2.0.0, master takes control of the ramp-up rate.
                // While ramping up, master sends multiple spawn messages. Now we stop previous threads before spawn,
                // may result in many creation and destruction of threads.
                this.stop();
                this.state = RunnerState.Spawning;
                this.onSpawnMessage(message);
                this.state = RunnerState.Running;
            } else if ("stop".equals(type)) {
                this.stop();

                if (null != Locust.getInstance().getRateLimiter()) {
                    Locust.getInstance().getRateLimiter().stop();
                }

                this.state = RunnerState.Stopped;
                logger.debug("Recv stop message from master, all the workers are stopped");
                try {
                    this.rpcClient.send(new Message("client_stopped", null, null, this.nodeID));
                    this.rpcClient.send(new Message("client_ready", null, null, this.nodeID));
                    this.state = RunnerState.Ready;
                } catch (IOException ex) {
                    logger.error("Error while switching from the state stopped to ready", ex);
                }
            }
        } else if (this.state == RunnerState.Stopped) {
            if ("spawn".equals(type) && spawnMessageIsValid(message)) {
                this.state = RunnerState.Spawning;
                this.onSpawnMessage(message);

                if (null != Locust.getInstance().getRateLimiter()) {
                    Locust.getInstance().getRateLimiter().start();
                }

                this.state = RunnerState.Running;
            }
        }
    }

    public void getReady() {
        this.executor = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r);
            }
        });
        this.state = RunnerState.Ready;
        try {
            this.rpcClient.send(new Message("client_ready", null, "-1", this.nodeID));
        } catch (IOException ex) {
            logger.error("Error while sending a message that the system is ready", ex);
        }

        this.executor.submit(new Receiver(this));
        this.executor.submit(new Sender(this));
        this.executor.submit(new Heartbeat(this));
    }

    private static class Receiver implements Runnable {
        private final Runner runner;

        private Receiver(Runner runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            Thread.currentThread().setName(name + "receive-from-client");
            while (true) {
                try {
                    Message message = runner.rpcClient.recv();
                    runner.onMessage(message);
                } catch (Exception ex) {
                    logger.error("Error while receiving a message", ex);
                }
            }
        }
    }

    private static class Sender implements Runnable {
        private final Runner runner;

        private Sender(Runner runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            Thread.currentThread().setName(name + "send-to-client");
            while (true) {
                try {
                    Map<String, Object> data = runner.stats.getMessageToRunnerQueue().take();
                    if (data.containsKey("current_cpu_usage")) {
                        // It's heartbeat message, moved to here to avoid race condition of zmq socket.
                        runner.rpcClient.send(new Message("heartbeat", data, null, runner.nodeID));
                        continue;
                    }
                    if (runner.state == RunnerState.Ready || runner.state == RunnerState.Stopped) {
                        continue;
                    }
                    data.put("user_count", runner.numClients);
                    runner.rpcClient.send(new Message("stats", data, null, runner.nodeID));
                } catch (InterruptedException ex) {
                    return;
                } catch (Exception ex) {
                    logger.error("Error in running the sender", ex);
                }
            }
        }
    }

    private static class Heartbeat implements Runnable {
        private static final int HEARTBEAT_INTERVAL = 1000;
        private final Runner runner;

        private final OperatingSystemMXBean osBean = getOsBean();

        private Heartbeat(Runner runner) {
            this.runner = runner;
        }

        @Override
        public void run() {
            String name = Thread.currentThread().getName();
            Thread.currentThread().setName(name + "heartbeat");
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    if (runner.isHeartbeatStopped()) {
                        continue;
                    }
                    Map<String, Object> data = new HashMap<>(2);
                    data.put("state", runner.state.name().toLowerCase());
                    data.put("current_cpu_usage", getCpuUsage());
                    boolean success = runner.stats.getMessageToRunnerQueue().offer(data);
                    if (!success) {
                        logger.error("Failed to insert heartbeat message to the queue");
                    }
                } catch (InterruptedException ex) {
                    return;
                } catch (Exception ex) {
                    logger.error("Error in running the heartbeat", ex);
                }
            }
        }

        private double getCpuUsage() {
            return osBean.getSystemCpuLoad() * 100;
        }

        private OperatingSystemMXBean getOsBean() {
            return (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        }
    }

}
