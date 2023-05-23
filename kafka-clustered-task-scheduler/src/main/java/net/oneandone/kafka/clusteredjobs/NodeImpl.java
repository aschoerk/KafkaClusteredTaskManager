package net.oneandone.kafka.clusteredjobs;

import static java.lang.management.ManagementFactory.*;
import static net.oneandone.kafka.clusteredjobs.SignalEnum.DOHEARTBEAT;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.oneandone.kafka.clusteredjobs.api.Container;
import net.oneandone.kafka.clusteredjobs.api.NodeTaskInformation;
import net.oneandone.kafka.clusteredjobs.api.TaskDefinition;
import net.oneandone.kafka.clusteredjobs.states.StateEnum;

/**
 * @author aschoerk
 */
public class NodeImpl extends StoppableBase implements net.oneandone.kafka.clusteredjobs.api.Node {

    static final long CONSUMER_POLL_TIME = 500L;

    static final Duration WAIT_IN_NEW_STATE = Duration.ofMillis(1000L);
    private static final Duration HEART_BEAT_PERIOD = Duration.ofMillis(1000);
    private static Logger logger = LoggerFactory.getLogger(NodeImpl.class);

    private static AtomicInteger nodeCounter = new AtomicInteger(0);
    private final NodeFactory nodeFactory;

    private ArrayList<Stoppable> stoppables = new ArrayList<>();

    private Clock clock = Clock.systemDefaultZone();

    private int nodeId;

    final String syncTopic;
    Logger log = LoggerFactory.getLogger(NodeImpl.class);

    private final String hostname;
    private final String processName;
    private Thread signalsReceivingThread;
    private Thread pendingHandlerThread;
    final String bootstrapServers;

    ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    Integer taskPartition = null;
    private transient Sender sender;
    private transient SignalHandler signalHandler;
    private transient PendingHandler pendingHandler;

    private Set<Thread> handlerThreads = new HashSet<>();
    private Container container;
    private NodeHeartbeat nodeHeartbeat;
    private NodeTaskInformationHandler nodeTaskInformationHandler;
    private String lastNodeTaskInformation;
    private SignalsWatcher signalsWatcher;

    /**
     * create a Node instance capable of executing clustered periodic tasks
     * @param container functionality provided by the container running the node
     * @param nodeFactory the factory to be used to create subcomponents of node.
     */
    public NodeImpl(Container container, NodeFactory nodeFactory) {
        this.container = container;
        this.nodeFactory = nodeFactory;
        this.syncTopic = container.getSyncTopicName();
        this.nodeId = nodeCounter.incrementAndGet();
        this.bootstrapServers = container.getBootstrapServers();
        try {
            hostname = Inet4Address.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new KctmException("NodeImpl cannot identify host", e);
        }
        processName = hostname + "_" + getRuntimeMXBean().getPid();
        nodeTaskInformationHandler = nodeFactory.createNodeTaskInformationHandler(this);
    }

    /**
     * current time as calculated by clock of node
     * @return current time as calculated by clock of node
     */
    public Instant getNow() {
        return Instant.now(clock);
    }

    /**
     * return the runtime-information of a registered Task
     * @param taskName the name of the task being requested
     * @return the runtime-information of a registered Task
     */
    public Task getTask(String taskName) {
        return this.tasks.get(taskName);
    }

    /**
     * return the container the Node is running
     * @return the container the Node is running
     */
    public Container getContainer() {
        return container;
    }

    /**
     * create the information about all tasks registered on the node
     * @return the information about all tasks registered on the node
     */
    @Override
    public NodeTaskInformation getNodeInformation() {
        NodeTaskInformationImpl result = new NodeTaskInformationImpl(getUniqueNodeId());
        tasks.entrySet().forEach(e -> {
            Task task = e.getValue();
            result.addTaskInformation(new NodeTaskInformationImpl.TaskInformationImpl(task));
        });
        return result;
    }

    /**
     * start the node
     */
    public void run() {
        if(isRunning()) {
            return;
        }

        pendingHandlerThread = getContainer().createThread(() ->
                {
                    stoppables.add(getPendingHandler());
                    getPendingHandler().run();
                    logger.info("stopped");
                }
        );
        pendingHandlerThread.start();
        setRunning();
        while (!threadsRunning()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new KctmException("Interrupted while waiting for node startup", e);
            }
        }
        this.nodeHeartbeat = nodeFactory.createNodeHeartbeat(HEART_BEAT_PERIOD);
        getPendingHandler().schedulePending(
                nodeFactory.createPendingEntry(
                        getNow().plus(Duration.ofMillis(1000L)),
                        "doHeartBeat" + getUniqueNodeId(),
                        () -> NodeImpl.this.getSender().sendSignal(null, DOHEARTBEAT)));



        signalsWatcher = nodeFactory.createSignalsWatcher(this);
        signalsReceivingThread = getContainer().createThread(() ->
                {
                    stoppables.add(signalsWatcher);
                    signalsWatcher.run();
                    logger.info("stopped");
                }
        );
        signalsReceivingThread.start();


        synchronized (this) {
            try {
                if (signalsWatcher.getWatcherStarting() == null) {
                    logger.info("Going to wait for SignalWatcher Topic consumer init.");
                    this.wait();
                    logger.info("End of   wait for SignalWatcher Topic consumer init.");
                }
            } catch (InterruptedException e) {
                throw new KctmException("Interrupted Startup of SignalsWatchwr");
            }
        }
        signalsWatcher.readOldSignals();
    }

    /**
     * register a task to be scheduled on node
     * @param taskDefinition the description how the task is to be executed
     * @return the runtime-representation of the registered task.
     */
    public Task register(TaskDefinition taskDefinition) {
        if(!isRunning()) {
            throw new KctmException("trying to register in not running node");
        }
        else {
            while (!threadsRunning()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new KctmException("Interrupted while waiting for node startup", e);
                }
            }
        }
        Task task = nodeFactory.createTask(this, taskDefinition);
        task.setLocalState(StateEnum.NULL);
        this.tasks.put(taskDefinition.getName(), task);
        getSignalHandler().handleInternalSignal(task, SignalEnum.INITIATING_I);
        getPendingHandler().scheduleWaitForNewSwitch(WAIT_IN_NEW_STATE);
        return task;
    }

    /**
     * provide function to create threads
     * @param runnable the runnable the thread should execute
     * @return the thread for the runnablel
     */
    public Thread newHandlerThread(final Runnable runnable) {
        Thread result = getContainer().createThread((runnable));
        handlerThreads.add(result);
        return result;
    }

    /**
     * dispose a thread previously created by newHandlerThread
     * @param thread the thread to be disposed
     */
    public void disposeHandlerThread(final Thread thread) {
        assert handlerThreads.remove(thread);
    }

    /**
     * return the id unique in the cluster of task executing nodes
     * @return the id unique in the cluster of task executing/scheduling nodes
     */
    public String getUniqueNodeId() {
        return processName + "_" + nodeId;
    }


    /**
     * stop all activities
     */
    @Override
    public void shutdown() {
        logger.info("Killing node: {}", getUniqueNodeId());
        stoppables.forEach(s -> s.shutdown());
        stoppables.clear();
        tasks.entrySet().forEach(e -> {
            Task t = e.getValue();
            if (t.getLocalState() == StateEnum.HANDLING_BY_NODE || t.getLocalState() == StateEnum.CLAIMED_BY_NODE) {
                getSignalHandler().handleInternalSignal(e.getValue(), SignalEnum.UNCLAIM_I);
            }
        });
        try {
            Thread.sleep(NodeImpl.CONSUMER_POLL_TIME + 1000);
        } catch (InterruptedException e) {
            throw new KctmException("During shutdown interrupted", e);
        }
        while (pendingHandlerThread.isAlive())
            pendingHandlerThread.interrupt();
        while (signalsReceivingThread.isAlive())
            signalsReceivingThread.interrupt();
        for (Thread t : handlerThreads) {
            while (t.isAlive()) {
                t.interrupt();
            }
        }
        getSender().getSyncProducer().close();
        logger.info("Killed  node: {}", getUniqueNodeId());
    }


    /**
     * return sender capabable of sending tasks to the sync-topic
     * @return sender capabable of sending tasks to the sync-topic
     */
    public Sender getSender() {
        if(sender == null) {
            synchronized (this) {
                if(sender == null) {
                    sender = nodeFactory.createSender(this);
                }
            }
        }
        return sender;
    }

    /**
     * return the object dispatching the signals into the Statemachine according to the state of the task
     * @return  the object dispatching the signals into the Statemachine according to the state of the task
     */
    public SignalHandler getSignalHandler() {
        if(signalHandler == null) {
            synchronized (this) {
                if(signalHandler == null) {
                    signalHandler = nodeFactory.createSignalHandler(this);
                }
            }
        }
        return signalHandler;
    }

    /**
     * return the object scheduling tasks in the future
     * @return the object scheduling tasks in the future
     */
    public PendingHandler getPendingHandler() {
        if(pendingHandler == null) {
            synchronized (this) {
                if(pendingHandler == null) {
                    pendingHandler = nodeFactory.createPendingHandler(this);
                }
            }
        }
        return pendingHandler;
    }

    NodeHeartbeat getNodeHeartbeat() {
        return nodeHeartbeat;
    }



    /**
     * change clock used for timestamps. be aware, that calculated durations are awaited by the wait-function of
     * Thread and Object
     * @param clockP the clock to be used
     */
    void setClock(final Clock clockP) {
        this.clock = clockP;
    }



    boolean threadsRunning() {
        return stoppables.stream().filter(s -> !s.isRunning()).findAny().isEmpty();
    }

    void sendNodeTaskInformation(boolean onlyIfChanged) {
        final String currentNodeTaskInformation = KbXStream.jsonXStream.toXML(getNodeInformation());
        if (!onlyIfChanged || !currentNodeTaskInformation.equals(lastNodeTaskInformation)) {
            lastNodeTaskInformation = currentNodeTaskInformation;
            getSender().getSyncProducer().send(new ProducerRecord(syncTopic, getUniqueNodeId(),
                    currentNodeTaskInformation));
        }
    }


    /**
     * get the object capable of handling initiatizing information for newly to be started node
     * @return the object capable of handling initiatizing information for newly to be started node
     */
    public NodeTaskInformationHandler getNodeTaskInformationHandler() {
        return nodeTaskInformationHandler;
    }

    SignalsWatcher getSignalsWatcher() {
        return signalsWatcher;
    }
}
