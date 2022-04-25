package com.hubspot.singularity.mesos;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Doubles;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.mesos.Resources;
import com.hubspot.singularity.SingularityAgentUsageWithId;
import com.hubspot.singularity.SingularityManagedThreadPoolFactory;
import com.hubspot.singularity.SingularityPendingTask;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskRequest;
import com.hubspot.singularity.config.MesosConfiguration;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.PriorityManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.TaskRequestManager;
import com.hubspot.singularity.data.usage.UsageManager;
import com.hubspot.singularity.helpers.SingularityMesosTaskHolder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.mesos.v1.Protos.Offer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SingularityPendingTaskQueueProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(
    SingularityPendingTaskQueueProcessor.class
  );

  private static final int PARALLEL_LOCK_WAIT = 15;
  private static final String LOCK_NAME = "pending-task-queue";

  private final LeaderLatch leaderLatch;
  private final SingularityOfferScoring offerScoring;
  private final SingularityMesosOfferManager offerManager;
  private final TaskRequestManager taskRequestManager;
  private final UsageManager usageManager;
  private final SingularityConfiguration configuration;
  private final MesosConfiguration mesosConfiguration;
  private final TaskManager taskManager;
  private final RequestManager requestManager;
  private final PriorityManager priorityManager;
  private final SingularityMesosTaskBuilder mesosTaskBuilder;
  private final SingularityTaskSizeOptimizer taskSizeOptimizer;
  private final PriorityBlockingQueue<SingularityPendingTaskId> preLockQueue;
  private final PriorityBlockingQueue<SingularityPendingTaskId> pendingTaskQueue;
  private final ExecutorService lockExecutor;
  private final ExecutorService queueExecutor;
  private final AtomicBoolean running;
  private final SingularitySchedulerMetrics metrics;
  private final SingularitySchedulerLock lock;
  private final Map<String, Long> lockStarts;
  private final Set<SingularityPendingTaskId> handled;
  private final SingularityMesosSchedulerClient schedulerClient;
  private final Resources defaultResources;
  private final Resources defaultCustomExecutorResources;

  private Future<?> queueFuture = null;
  private Future<?> lockFuture = null;

  @Inject
  public SingularityPendingTaskQueueProcessor(
    LeaderLatch leaderLatch,
    SingularityOfferScoring offerScoring,
    SingularityMesosOfferManager offerManager,
    UsageManager usageManager,
    SingularityConfiguration configuration,
    TaskRequestManager taskRequestManager,
    TaskManager taskManager,
    RequestManager requestManager,
    PriorityManager priorityManager,
    SingularityManagedThreadPoolFactory threadPoolFactory,
    SingularitySchedulerMetrics metrics,
    SingularitySchedulerLock lock,
    SingularityMesosSchedulerClient schedulerClient,
    SingularityMesosTaskBuilder mesosTaskBuilder,
    SingularityTaskSizeOptimizer taskSizeOptimizer
  ) {
    this.leaderLatch = leaderLatch;
    this.offerScoring = offerScoring;
    this.offerManager = offerManager;
    this.usageManager = usageManager;
    this.configuration = configuration;
    this.mesosConfiguration = configuration.getMesosConfiguration();
    this.taskRequestManager = taskRequestManager;
    this.taskManager = taskManager;
    this.requestManager = requestManager;
    this.priorityManager = priorityManager;
    this.pendingTaskQueue = new PriorityBlockingQueue<>();
    this.preLockQueue =
      new PriorityBlockingQueue<>(
        11,
        (a, b) -> Doubles.compare(getWeightedPriority(a), getWeightedPriority(b))
      );
    this.queueExecutor = threadPoolFactory.get("pending-task-queue", 2);
    this.lockExecutor =
      threadPoolFactory.get("pending-task-lock-wait", PARALLEL_LOCK_WAIT);
    this.running = new AtomicBoolean(false);
    this.metrics = metrics;
    this.lock = lock;
    this.schedulerClient = schedulerClient;
    this.mesosTaskBuilder = mesosTaskBuilder;
    this.taskSizeOptimizer = taskSizeOptimizer;
    this.lockStarts = new ConcurrentHashMap<>();
    this.handled = new HashSet<>();
    this.defaultResources =
      new Resources(
        mesosConfiguration.getDefaultCpus(),
        mesosConfiguration.getDefaultMemory(),
        0,
        mesosConfiguration.getDefaultDisk()
      );
    this.defaultCustomExecutorResources =
      new Resources(
        configuration.getCustomExecutorConfiguration().getNumCpus(),
        configuration.getCustomExecutorConfiguration().getMemoryMb(),
        0,
        configuration.getCustomExecutorConfiguration().getDiskMb()
      );
  }

  public void start() {
    if (leaderLatch.hasLeadership()) {
      if (lockFuture != null) {
        lockFuture.cancel(true);
      }
      if (queueFuture != null) {
        queueFuture.cancel(true);
      }
      running.set(true);
      queueFuture = queueExecutor.submit(this::run);
      lockFuture = queueExecutor.submit(this::runLockWait);
    }
  }

  public void addPendingTaskIfNotInQueue(SingularityPendingTaskId pendingTask) {
    synchronized (handled) {
      if (!handled.contains(pendingTask)) {
        preLockQueue.put(pendingTask);
        handled.add(pendingTask);
      }
    }
  }

  public Set<SingularityPendingTaskId> getHandledTasks() {
    synchronized (handled) {
      return new HashSet<>(handled);
    }
  }

  // Method only for testing. Need a synchronous way of waiting for task launch in unit tests
  @VisibleForTesting
  CompletableFuture<Boolean> drainHandledTasks(long timeout) {
    return CompletableFuture.supplyAsync(
      () -> {
        long start = System.currentTimeMillis();
        while (!handled.isEmpty()) {
          try {
            Thread.sleep(50);
          } catch (InterruptedException ie) {
            LOG.warn("Interrupted waiting for tasks to schedule");
          }
          if (System.currentTimeMillis() - start > timeout) {
            return false;
          }
        }
        return true;
      }
    );
  }

  public void removePendingTask(SingularityPendingTaskId toRemove) {
    synchronized (handled) {
      if (!preLockQueue.remove(toRemove)) {
        if (!pendingTaskQueue.remove(toRemove)) {
          LOG.warn("Unable to remove pending task {}", toRemove.getId());
        }
      }
    }
  }

  public void stop() {
    running.set(false);
    if (queueFuture != null) {
      queueFuture.cancel(false);
    }
  }

  private void runLockWait() {
    Map<SingularityPendingTaskId, CompletableFuture<Void>> waitFutures = new HashMap<>();
    while (running.get()) {
      long start = System.currentTimeMillis();
      try {
        if (waitFutures.size() < PARALLEL_LOCK_WAIT) {
          SingularityPendingTaskId pendingTask = preLockQueue.poll();
          waitFutures.put(
            pendingTask,
            CompletableFuture.runAsync(
              () -> waitLockAndEnqueue(start, pendingTask),
              lockExecutor
            )
          );
        } else {
          Set<SingularityPendingTaskId> keys = waitFutures.keySet();
          for (SingularityPendingTaskId key : keys) {
            if (waitFutures.get(key).isDone()) {
              waitFutures.remove(key);
            }
          }
        }
      } catch (Exception e) {
        LOG.error("Error running task launch", e);
      }
    }
  }

  private void waitLockAndEnqueue(long start, SingularityPendingTaskId pendingTaskId) {
    try {
      lockStarts.put(
        pendingTaskId.getId(),
        lock.lock(pendingTaskId.getRequestId(), LOCK_NAME)
      );
      LOG.debug(
        "Got request lock to launch pending task after {}",
        System.currentTimeMillis() - start
      );
      synchronized (pendingTaskQueue) {
        pendingTaskQueue.put(pendingTaskId);
      }
    } catch (Exception e) {
      LOG.error("Could not acquire lock for task {}", pendingTaskId, e);
      lock.unlock(
        pendingTaskId.getRequestId(),
        LOCK_NAME,
        Optional
          .ofNullable(lockStarts.remove(pendingTaskId.getId()))
          .orElse(System.currentTimeMillis())
      );
    }
  }

  // Everything in this method is run behind a request-level lock and offer lock
  private void run() {
    while (running.get()) {
      long start = System.currentTimeMillis();
      SingularityPendingTaskId pendingTaskId = pendingTaskQueue.poll();
      try {
        Optional<SingularityPendingTask> maybePendingTask = taskManager.getPendingTask(
          pendingTaskId
        );
        if (!maybePendingTask.isPresent()) {
          LOG.error("Could not find pending task for id {}", pendingTaskId);
          return;
        }
        SingularityPendingTask toLaunch = maybePendingTask.get();
        SingularityTaskRequestHolder taskRequestHolder = new SingularityTaskRequestHolder(
          taskRequestManager.getTaskRequest(toLaunch),
          defaultResources,
          defaultCustomExecutorResources
        );
        List<SingularityOfferHolder> offers = offerManager.checkoutOffers();
        SingularityOfferHolder bestOffer = null;
        double maxOfferScore = 0.0;
        for (SingularityOfferHolder offer : offers) {
          double offerScore = scoreOffer(taskRequestHolder, offer);
          if (offerScore > maxOfferScore) {
            bestOffer = offer;
          }
          if (maxOfferScore >= mesosConfiguration.getGoodEnoughScoreThreshold()) {
            break;
          }
        }
        if (bestOffer != null) {
          acceptTask(bestOffer, taskRequestHolder);
          List<Offer> usedOffers = bestOffer.getOffers();
          List<Offer> unused = bestOffer.launchTasksAndGetUnusedOffers(schedulerClient);
          usedOffers.removeAll(unused);
          usedOffers.stream().map(Offer::getId).forEach(offerManager::useOffer);
          unused.stream().map(Offer::getId).forEach(offerManager::returnOffer);

          synchronized (handled) {
            handled.remove(pendingTaskId);
          }
        } else {
          // Back on the queue and try again. Temporarily free up lock for other pollers first
          preLockQueue.put(toLaunch.getPendingTaskId());
        }
        offers.remove(bestOffer);
        offers
          .stream()
          .flatMap(s -> s.getOffers().stream())
          .map(Offer::getId)
          .forEach(offerManager::returnOffer);

        metrics.getOfferLoopTime().update(System.currentTimeMillis() - start);
      } catch (Exception e) {
        LOG.error("Error running task launch", e);
      } finally {
        lock.unlock(
          pendingTaskId.getRequestId(),
          LOCK_NAME,
          Optional
            .ofNullable(lockStarts.remove(pendingTaskId.getId()))
            .orElse(System.currentTimeMillis())
        );
      }
    }
  }

  private double scoreOffer(
    SingularityTaskRequestHolder taskRequestHolder,
    SingularityOfferHolder offer
  ) {
    Optional<SingularityAgentUsageWithId> maybeUsage = checkRecalculateAndGetUsage(offer);
    if (!maybeUsage.isPresent()) {
      // Update usage async
      return 0;
    }
    return offerScoring.score(
      taskRequestHolder,
      offer,
      new SingularityAgentUsageWithCalculatedScores(
        maybeUsage.get(),
        mesosConfiguration.getScoreUsingSystemLoad(),
        offerScoring.getMaxProbableUsageForAgent(
          offer.getSanitizedHost(),
          defaultResources
        ),
        mesosConfiguration.getLoad5OverloadedThreshold(),
        mesosConfiguration.getLoad1OverloadedThreshold(),
        maybeUsage.get().getTimestamp()
      )
    );
  }

  private Optional<SingularityAgentUsageWithId> checkRecalculateAndGetUsage(
    SingularityOfferHolder offerHolder
  ) {
    Optional<SingularityAgentUsageWithId> maybeUsage = usageManager.getAgentUsage(
      offerHolder.getAgentId()
    );
    if (configuration.isReCheckMetricsForLargeNewTaskCount() && maybeUsage.isPresent()) {
      long newTaskCount = taskManager
        .getActiveTaskIds()
        .stream()
        .filter(
          t ->
            t.getStartedAt() > maybeUsage.get().getTimestamp() &&
            t.getSanitizedHost().equals(offerHolder.getSanitizedHost())
        )
        .count();
      if (newTaskCount >= maybeUsage.get().getNumTasks() / 2) {
        // TODO kick off new usage collection async
        return Optional.empty();
      }
    }
    if (!maybeUsage.isPresent()) {
      // TODO kick off new usage collection async
      return Optional.empty();
    }
    return maybeUsage;
  }

  private double getWeightedPriority(SingularityPendingTaskId pendingTaskId) {
    long overdueMillis = Math.max(
      System.currentTimeMillis() - pendingTaskId.getNextRunAt(),
      1
    );
    Double requestPriority = requestManager
      .getRequest(pendingTaskId.getRequestId())
      .map(r -> priorityManager.getTaskPriorityLevelForRequest(r.getRequest()))
      .orElse(configuration.getDefaultTaskPriorityLevel());
    return (
      overdueMillis *
      Math.pow(requestPriority, configuration.getSchedulerPriorityWeightFactor())
    );
  }

  private synchronized void acceptTask(
    SingularityOfferHolder offerHolder,
    SingularityTaskRequestHolder taskRequestHolder
  ) {
    final SingularityTaskRequest taskRequest = taskRequestHolder.getTaskRequest();
    final SingularityMesosTaskHolder taskHolder = mesosTaskBuilder.buildTask(
      offerHolder,
      offerHolder.getCurrentResources(),
      taskRequest,
      taskRequestHolder.getTaskResources(),
      taskRequestHolder.getExecutorResources()
    );

    final SingularityTask zkTask = taskSizeOptimizer.getSizeOptimizedTask(taskHolder);

    LOG.trace("Accepted and built task {}", zkTask);
    LOG.info(
      "Launching task {} slot on agent {} ({})",
      taskHolder.getTask().getTaskId(),
      offerHolder.getAgentId(),
      offerHolder.getHostname()
    );
    LOG.trace(
      "Task {} offer resource usage: {} / {}",
      taskHolder.getTask().getTaskId(),
      taskHolder.getMesosTask().getResourcesList(),
      offerHolder.getCurrentResources()
    );

    taskManager.createTaskAndDeletePendingTask(zkTask);
    offerHolder.addMatchedTask(taskHolder);
  }
}
