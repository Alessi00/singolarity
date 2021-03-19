package com.hubspot.singularity.scheduler;

import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.hubspot.baragon.models.BaragonRequestState;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.DeployState;
import com.hubspot.singularity.LoadBalancerRequestType;
import com.hubspot.singularity.LoadBalancerRequestType.LoadBalancerRequestId;
import com.hubspot.singularity.RequestState;
import com.hubspot.singularity.RequestType;
import com.hubspot.singularity.SingularityDeploy;
import com.hubspot.singularity.SingularityDeployFailure;
import com.hubspot.singularity.SingularityDeployKey;
import com.hubspot.singularity.SingularityDeployMarker;
import com.hubspot.singularity.SingularityDeployProgress;
import com.hubspot.singularity.SingularityDeployResult;
import com.hubspot.singularity.SingularityLoadBalancerUpdate;
import com.hubspot.singularity.SingularityManagedThreadPoolFactory;
import com.hubspot.singularity.SingularityPendingDeploy;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityPendingRequest.PendingType;
import com.hubspot.singularity.SingularityPendingRequestBuilder;
import com.hubspot.singularity.SingularityPendingTask;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.SingularityRequestHistory.RequestHistoryType;
import com.hubspot.singularity.SingularityRequestWithState;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskCleanup;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityUpdatePendingDeployRequest;
import com.hubspot.singularity.TaskCleanupType;
import com.hubspot.singularity.api.SingularityRunNowRequest;
import com.hubspot.singularity.async.CompletableFutures;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.usage.UsageManager;
import com.hubspot.singularity.expiring.SingularityExpiringPause;
import com.hubspot.singularity.expiring.SingularityExpiringScale;
import com.hubspot.singularity.hooks.LoadBalancerClient;
import com.hubspot.singularity.mesos.SingularitySchedulerLock;
import com.hubspot.singularity.scheduler.SingularityDeployHealthHelper.DeployHealth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SingularityDeployChecker {
  private static final Logger LOG = LoggerFactory.getLogger(
    SingularityDeployChecker.class
  );

  private final DeployManager deployManager;
  private final TaskManager taskManager;
  private final SingularityDeployHealthHelper deployHealthHelper;
  private final RequestManager requestManager;
  private final SingularityDeployCheckHelper deployCheckHelper;
  private final SingularityConfiguration configuration;
  private final LoadBalancerClient lbClient;
  private final SingularitySchedulerLock lock;
  private final UsageManager usageManager;
  private final ExecutorService deployCheckExecutor;

  @Inject
  public SingularityDeployChecker(
    DeployManager deployManager,
    SingularityDeployHealthHelper deployHealthHelper,
    LoadBalancerClient lbClient,
    RequestManager requestManager,
    TaskManager taskManager,
    SingularityDeployCheckHelper deployCheckHelper,
    SingularityConfiguration configuration,
    SingularitySchedulerLock lock,
    UsageManager usageManager,
    SingularityManagedThreadPoolFactory threadPoolFactory
  ) {
    this.configuration = configuration;
    this.lbClient = lbClient;
    this.deployHealthHelper = deployHealthHelper;
    this.requestManager = requestManager;
    this.deployManager = deployManager;
    this.taskManager = taskManager;
    this.lock = lock;
    this.usageManager = usageManager;
    this.deployCheckHelper = deployCheckHelper;
    this.deployCheckExecutor =
      threadPoolFactory.get("deploy-checker", configuration.getCoreThreadpoolSize());
  }

  public int checkDeploys() {
    final List<SingularityPendingDeploy> pendingDeploys = deployManager.getPendingDeploys();
    final List<SingularityDeployMarker> cancelDeploys = deployManager.getCancelDeploys();
    final List<SingularityUpdatePendingDeployRequest> updateRequests = deployManager.getPendingDeployUpdates();

    if (pendingDeploys.isEmpty() && cancelDeploys.isEmpty()) {
      return 0;
    }

    final Map<SingularityPendingDeploy, SingularityDeployKey> pendingDeployToKey = SingularityDeployKey.fromPendingDeploys(
      pendingDeploys
    );
    final Map<SingularityDeployKey, SingularityDeploy> deployKeyToDeploy = deployManager.getDeploysForKeys(
      pendingDeployToKey.values()
    );

    CompletableFutures
      .allOf(
        pendingDeploys
          .stream()
          .map(
            pendingDeploy ->
              CompletableFuture.runAsync(
                () ->
                  lock.runWithRequestLock(
                    () ->
                      checkDeploy(
                        pendingDeploy,
                        cancelDeploys,
                        pendingDeployToKey,
                        deployKeyToDeploy,
                        updateRequests
                      ),
                    pendingDeploy.getDeployMarker().getRequestId(),
                    getClass().getSimpleName()
                  ),
                deployCheckExecutor
              )
          )
          .collect(Collectors.toList())
      )
      .join();

    cancelDeploys.forEach(deployManager::deleteCancelDeployRequest);
    updateRequests.forEach(deployManager::deleteUpdatePendingDeployRequest);

    return pendingDeploys.size();
  }

  private void checkDeploy(
    final SingularityPendingDeploy pendingDeploy,
    final List<SingularityDeployMarker> cancelDeploys,
    final Map<SingularityPendingDeploy, SingularityDeployKey> pendingDeployToKey,
    final Map<SingularityDeployKey, SingularityDeploy> deployKeyToDeploy,
    List<SingularityUpdatePendingDeployRequest> updateRequests
  ) {
    final SingularityDeployKey deployKey = pendingDeployToKey.get(pendingDeploy);
    final Optional<SingularityDeploy> deploy = Optional.ofNullable(
      deployKeyToDeploy.get(deployKey)
    );

    Optional<SingularityRequestWithState> maybeRequestWithState = requestManager.getRequest(
      pendingDeploy.getDeployMarker().getRequestId()
    );
    if (deployCheckHelper.isNotInDeployableState(maybeRequestWithState)) {
      LOG.warn(
        "Deploy {} request was {}, removing deploy",
        pendingDeploy,
        SingularityRequestWithState.getRequestState(maybeRequestWithState)
      );

      if (SingularityDeployCheckHelper.shouldCancelLoadBalancer(pendingDeploy)) {
        cancelLoadBalancer(pendingDeploy, SingularityDeployFailure.deployRemoved());
      }

      failPendingDeployDueToState(pendingDeploy, maybeRequestWithState, deploy);
      return;
    }

    final SingularityDeployMarker pendingDeployMarker = pendingDeploy.getDeployMarker();

    final Optional<SingularityDeployMarker> cancelRequest = SingularityDeployCheckHelper.findCancel(
      cancelDeploys,
      pendingDeployMarker
    );
    final Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest = SingularityDeployCheckHelper.findUpdateRequest(
      updateRequests,
      pendingDeploy
    );

    final SingularityRequestWithState requestWithState = maybeRequestWithState.get();
    final SingularityRequest request = pendingDeploy
      .getUpdatedRequest()
      .orElse(requestWithState.getRequest());

    final List<SingularityTaskId> requestTasks = taskManager.getTaskIdsForRequest(
      request.getId()
    );
    final List<SingularityTaskId> activeTasks = taskManager.filterActiveTaskIds(
      requestTasks
    );

    final List<SingularityTaskId> inactiveDeployMatchingTasks = new ArrayList<>(
      requestTasks.size()
    );

    for (SingularityTaskId taskId : requestTasks) {
      if (
        taskId.getDeployId().equals(pendingDeployMarker.getDeployId()) &&
        !activeTasks.contains(taskId)
      ) {
        inactiveDeployMatchingTasks.add(taskId);
      }
    }

    final List<SingularityTaskId> deployMatchingTasks = new ArrayList<>(
      activeTasks.size()
    );
    final List<SingularityTaskId> allOtherMatchingTasks = new ArrayList<>(
      activeTasks.size()
    );

    for (SingularityTaskId taskId : activeTasks) {
      if (taskId.getDeployId().equals(pendingDeployMarker.getDeployId())) {
        deployMatchingTasks.add(taskId);
      } else {
        allOtherMatchingTasks.add(taskId);
      }
    }

    SingularityDeployResult deployResult = getDeployResultSafe(
      request,
      requestWithState.getState(),
      cancelRequest,
      pendingDeploy,
      updatePendingDeployRequest,
      deploy,
      deployMatchingTasks,
      allOtherMatchingTasks,
      inactiveDeployMatchingTasks
    );

    LOG.info(
      "Deploy {} had result {} after {}",
      pendingDeployMarker,
      deployResult,
      JavaUtils.durationFromMillis(
        System.currentTimeMillis() - pendingDeployMarker.getTimestamp()
      )
    );

    if (deployResult.getDeployState() == DeployState.SUCCEEDED) {
      if (saveNewDeployState(pendingDeployMarker, Optional.of(pendingDeployMarker))) {
        if (request.getRequestType() == RequestType.ON_DEMAND) {
          deleteOrRecreatePendingTasks(pendingDeploy);
        } else if (request.getRequestType() != RequestType.RUN_ONCE) {
          deleteObsoletePendingTasks(pendingDeploy);
        }
        finishDeploy(
          requestWithState,
          deploy,
          pendingDeploy,
          allOtherMatchingTasks,
          deployResult
        );
        return;
      } else {
        LOG.warn(
          "Failing deploy {} because it failed to save deploy state",
          pendingDeployMarker
        );
        deployResult =
          new SingularityDeployResult(
            DeployState.FAILED_INTERNAL_STATE,
            Optional.of(
              String.format(
                "Deploy had state %s but failed to persist it correctly",
                deployResult.getDeployState()
              )
            ),
            deployResult.getLbUpdate(),
            SingularityDeployFailure.failedToSave(),
            deployResult.getTimestamp()
          );
      }
    } else if (!deployResult.getDeployState().isDeployFinished()) {
      return;
    }

    // success case is handled, handle failure cases:
    saveNewDeployState(pendingDeployMarker, Optional.empty());
    finishDeploy(
      requestWithState,
      deploy,
      pendingDeploy,
      deployMatchingTasks,
      deployResult
    );
  }

  private void deleteOrRecreatePendingTasks(SingularityPendingDeploy pendingDeploy) {
    List<SingularityPendingTaskId> obsoletePendingTasks = new ArrayList<>();

    taskManager
      .getPendingTaskIdsForRequest(pendingDeploy.getDeployMarker().getRequestId())
      .forEach(
        taskId -> {
          if (
            !taskId.getDeployId().equals(pendingDeploy.getDeployMarker().getDeployId())
          ) {
            if (taskId.getPendingType() == PendingType.ONEOFF) {
              Optional<SingularityPendingTask> maybePendingTask = taskManager.getPendingTask(
                taskId
              );
              if (maybePendingTask.isPresent()) {
                // Reschedule any user-initiated pending tasks under the new deploy
                SingularityPendingTask pendingTask = maybePendingTask.get();
                requestManager.addToPendingQueue(
                  SingularityDeployCheckHelper.buildPendingRequest(
                    pendingTask,
                    pendingDeploy
                  )
                );
              }
            }
            obsoletePendingTasks.add(taskId);
          }
        }
      );

    for (SingularityPendingTaskId pendingTaskId : obsoletePendingTasks) {
      LOG.debug("Deleting obsolete pending task {}", pendingTaskId.getId());
      taskManager.deletePendingTask(pendingTaskId);
    }
  }

  private void deleteObsoletePendingTasks(SingularityPendingDeploy pendingDeploy) {
    List<SingularityPendingTaskId> obsoletePendingTasks = taskManager
      .getPendingTaskIdsForRequest(pendingDeploy.getDeployMarker().getRequestId())
      .stream()
      .filter(
        taskId ->
          !taskId.getDeployId().equals(pendingDeploy.getDeployMarker().getDeployId())
      )
      .collect(Collectors.toList());

    for (SingularityPendingTaskId pendingTaskId : obsoletePendingTasks) {
      LOG.debug("Deleting obsolete pending task {}", pendingTaskId.getId());
      taskManager.deletePendingTask(pendingTaskId);
    }
  }

  private void updateLoadBalancerStateForTasks(
    Collection<SingularityTaskId> taskIds,
    LoadBalancerRequestType type,
    SingularityLoadBalancerUpdate update
  ) {
    for (SingularityTaskId taskId : taskIds) {
      taskManager.saveLoadBalancerState(taskId, type, update);
    }
  }

  private void cleanupTasks(
    SingularityPendingDeploy pendingDeploy,
    SingularityRequest request,
    SingularityDeployResult deployResult,
    Iterable<SingularityTaskId> tasksToKill
  ) {
    for (SingularityTaskId matchingTask : tasksToKill) {
      taskManager.saveTaskCleanup(
        new SingularityTaskCleanup(
          pendingDeploy.getDeployMarker().getUser(),
          SingularityDeployCheckHelper.getCleanupType(
            pendingDeploy,
            request,
            deployResult
          ),
          deployResult.getTimestamp(),
          matchingTask,
          Optional.of(
            String.format(
              "Deploy %s - %s",
              pendingDeploy.getDeployMarker().getDeployId(),
              deployResult.getDeployState().name()
            )
          ),
          Optional.empty(),
          Optional.empty()
        )
      );
    }
  }

  private boolean saveNewDeployState(
    SingularityDeployMarker pendingDeployMarker,
    Optional<SingularityDeployMarker> newActiveDeploy
  ) {
    Optional<SingularityRequestDeployState> deployState = deployManager.getRequestDeployState(
      pendingDeployMarker.getRequestId()
    );

    if (!deployState.isPresent()) {
      LOG.error(
        "Expected deploy state for deploy marker: {} but didn't find it",
        pendingDeployMarker
      );
      return false;
    }

    deployManager.saveNewRequestDeployState(
      new SingularityRequestDeployState(
        deployState.get().getRequestId(),
        newActiveDeploy.isPresent()
          ? newActiveDeploy
          : deployState.get().getActiveDeploy(),
        Optional.empty()
      )
    );

    return true;
  }

  private void finishDeploy(
    SingularityRequestWithState requestWithState,
    Optional<SingularityDeploy> deploy,
    SingularityPendingDeploy pendingDeploy,
    Iterable<SingularityTaskId> tasksToKill,
    SingularityDeployResult deployResult
  ) {
    SingularityRequest request = requestWithState.getRequest();

    if (!request.isOneOff() && !(request.getRequestType() == RequestType.RUN_ONCE)) {
      cleanupTasks(pendingDeploy, request, deployResult, tasksToKill);
    }

    if (deploy.isPresent() && deploy.get().getRunImmediately().isPresent()) {
      String requestId = deploy.get().getRequestId();
      String deployId = deploy.get().getId();
      SingularityRunNowRequest runNowRequest = deploy.get().getRunImmediately().get();
      List<SingularityTaskId> activeTasks = taskManager.getActiveTaskIdsForRequest(
        requestId
      );
      List<SingularityPendingTaskId> pendingTasks = taskManager.getPendingTaskIdsForRequest(
        requestId
      );

      SingularityPendingRequestBuilder builder = SingularityDeployCheckHelper.buildBasePendingRequest(
        request,
        deployId,
        deployResult,
        pendingDeploy,
        runNowRequest
      );
      PendingType pendingType = SingularityDeployCheckHelper.computePendingType(
        request,
        activeTasks,
        pendingTasks
      );
      if (pendingType != null) {
        builder.setPendingType(
          SingularityDeployCheckHelper.canceledOr(
            deployResult.getDeployState(),
            pendingType
          )
        );
        requestManager.addToPendingQueue(builder.build());
      } else {
        LOG.warn("Could not determine pending type for deploy {}.", deployId);
      }
    } else if (!request.isDeployable() && !request.isOneOff()) {
      PendingType pendingType = SingularityDeployCheckHelper.canceledOr(
        deployResult.getDeployState(),
        PendingType.NEW_DEPLOY
      );
      requestManager.addToPendingQueue(
        new SingularityPendingRequest(
          request.getId(),
          pendingDeploy.getDeployMarker().getDeployId(),
          deployResult.getTimestamp(),
          pendingDeploy.getDeployMarker().getUser(),
          pendingType,
          deploy.isPresent()
            ? deploy.get().getSkipHealthchecksOnDeploy()
            : Optional.empty(),
          pendingDeploy.getDeployMarker().getMessage()
        )
      );
    }

    if (deployResult.getDeployState() == DeployState.SUCCEEDED) {
      if (request.isDeployable() && !request.isOneOff()) {
        // remove the lock on bounces in case we deployed during a bounce
        requestManager.markBounceComplete(request.getId());
        requestManager.removeExpiringBounce(request.getId());
      }
      if (requestWithState.getState() == RequestState.FINISHED) {
        // A FINISHED request is moved to ACTIVE state so we can reevaluate the schedule
        requestManager.activate(
          request,
          RequestHistoryType.UPDATED,
          System.currentTimeMillis(),
          deploy.isPresent() ? deploy.get().getUser() : Optional.empty(),
          Optional.empty()
        );
      }
      // Clear utilization since a new deploy will update usage patterns
      // do this async so sql isn't on the main scheduling path for deploys
      CompletableFuture
        .runAsync(
          () -> usageManager.deleteRequestUtilization(request.getId()),
          deployCheckExecutor
        )
        .exceptionally(
          t -> {
            LOG.error("Could not clear usage data after new deploy", t);
            return null;
          }
        );
    }

    deployManager.saveDeployResult(pendingDeploy.getDeployMarker(), deploy, deployResult);

    if (
      request.isDeployable() &&
      (
        deployResult.getDeployState() == DeployState.CANCELED ||
        deployResult.getDeployState() == DeployState.FAILED ||
        deployResult.getDeployState() == DeployState.OVERDUE
      )
    ) {
      Optional<SingularityRequestDeployState> maybeRequestDeployState = deployManager.getRequestDeployState(
        request.getId()
      );
      if (
        maybeRequestDeployState.isPresent() &&
        maybeRequestDeployState.get().getActiveDeploy().isPresent() &&
        !(
          requestWithState.getState() == RequestState.PAUSED ||
          requestWithState.getState() == RequestState.DEPLOYING_TO_UNPAUSE
        )
      ) {
        requestManager.addToPendingQueue(
          new SingularityPendingRequest(
            request.getId(),
            maybeRequestDeployState.get().getActiveDeploy().get().getDeployId(),
            deployResult.getTimestamp(),
            pendingDeploy.getDeployMarker().getUser(),
            deployResult.getDeployState() == DeployState.CANCELED
              ? PendingType.DEPLOY_CANCELLED
              : PendingType.DEPLOY_FAILED,
            request.getSkipHealthchecks(),
            pendingDeploy.getDeployMarker().getMessage()
          )
        );
      }
    }

    if (deployResult.getDeployState() == DeployState.SUCCEEDED) {
      List<SingularityTaskId> newDeployCleaningTasks = taskManager
        .getCleanupTaskIds()
        .stream()
        .filter(
          t -> t.getDeployId().equals(pendingDeploy.getDeployMarker().getDeployId())
        )
        .collect(Collectors.toList());
      // Account for any bounce/decom that may have happened during the deploy
      if (!newDeployCleaningTasks.isEmpty()) {
        requestManager.addToPendingQueue(
          new SingularityPendingRequest(
            request.getId(),
            pendingDeploy.getDeployMarker().getDeployId(),
            deployResult.getTimestamp(),
            pendingDeploy.getDeployMarker().getUser(),
            PendingType.DEPLOY_FINISHED,
            request.getSkipHealthchecks(),
            pendingDeploy.getDeployMarker().getMessage()
          )
        );
      }
    }

    if (
      request.isDeployable() &&
      deployResult.getDeployState() == DeployState.SUCCEEDED &&
      pendingDeploy.getDeployProgress().isPresent() &&
      requestWithState.getState() != RequestState.PAUSED
    ) {
      if (
        pendingDeploy.getDeployProgress().get().getTargetActiveInstances() !=
        request.getInstancesSafe()
      ) {
        requestManager.addToPendingQueue(
          new SingularityPendingRequest(
            request.getId(),
            pendingDeploy.getDeployMarker().getDeployId(),
            deployResult.getTimestamp(),
            pendingDeploy.getDeployMarker().getUser(),
            PendingType.UPDATED_REQUEST,
            request.getSkipHealthchecks(),
            pendingDeploy.getDeployMarker().getMessage()
          )
        );
      }
    }

    if (requestWithState.getState() == RequestState.DEPLOYING_TO_UNPAUSE) {
      if (deployResult.getDeployState() == DeployState.SUCCEEDED) {
        requestManager.activate(
          request,
          RequestHistoryType.DEPLOYED_TO_UNPAUSE,
          deployResult.getTimestamp(),
          pendingDeploy.getDeployMarker().getUser(),
          Optional.empty()
        );
        requestManager.deleteExpiringObject(
          SingularityExpiringPause.class,
          request.getId()
        );
      } else {
        requestManager.pause(
          request,
          deployResult.getTimestamp(),
          pendingDeploy.getDeployMarker().getUser(),
          Optional.empty()
        );
      }
    }

    if (
      pendingDeploy.getUpdatedRequest().isPresent() &&
      deployResult.getDeployState() == DeployState.SUCCEEDED
    ) {
      requestManager.update(
        pendingDeploy.getUpdatedRequest().get(),
        System.currentTimeMillis(),
        pendingDeploy.getDeployMarker().getUser(),
        Optional.empty()
      );
      requestManager.deleteExpiringObject(
        SingularityExpiringScale.class,
        request.getId()
      );
    }

    removePendingDeploy(pendingDeploy);
  }

  private void removePendingDeploy(SingularityPendingDeploy pendingDeploy) {
    deployManager.deletePendingDeploy(pendingDeploy.getDeployMarker().getRequestId());
  }

  private void failPendingDeployDueToState(
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityRequestWithState> maybeRequestWithState,
    Optional<SingularityDeploy> deploy
  ) {
    SingularityDeployResult deployResult = new SingularityDeployResult(
      DeployState.FAILED,
      Optional.of(
        String.format(
          "Request in state %s is not deployable",
          SingularityRequestWithState.getRequestState(maybeRequestWithState)
        )
      ),
      Optional.empty()
    );
    if (!maybeRequestWithState.isPresent()) {
      deployManager.saveDeployResult(
        pendingDeploy.getDeployMarker(),
        deploy,
        deployResult
      );
      removePendingDeploy(pendingDeploy);
      return;
    }

    saveNewDeployState(pendingDeploy.getDeployMarker(), Optional.empty());
    finishDeploy(
      maybeRequestWithState.get(),
      deploy,
      pendingDeploy,
      Collections.emptyList(),
      deployResult
    );
  }

  private void updatePendingDeploy(
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityLoadBalancerUpdate> lbUpdate,
    DeployState deployState,
    Optional<SingularityDeployProgress> deployProgress
  ) {
    SingularityPendingDeploy copy = new SingularityPendingDeploy(
      pendingDeploy.getDeployMarker(),
      lbUpdate,
      deployState,
      deployProgress,
      pendingDeploy.getUpdatedRequest()
    );

    deployManager.savePendingDeploy(copy);
  }

  private void updatePendingDeploy(
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityLoadBalancerUpdate> lbUpdate,
    DeployState deployState
  ) {
    updatePendingDeploy(
      pendingDeploy,
      lbUpdate,
      deployState,
      pendingDeploy.getDeployProgress()
    );
  }

  private SingularityLoadBalancerUpdate sendCancelToLoadBalancer(
    SingularityPendingDeploy pendingDeploy
  ) {
    return lbClient.cancel(
      SingularityDeployCheckHelper.getLoadBalancerRequestId(pendingDeploy)
    );
  }

  private SingularityDeployResult cancelLoadBalancer(
    SingularityPendingDeploy pendingDeploy,
    List<SingularityDeployFailure> deployFailures
  ) {
    final SingularityLoadBalancerUpdate lbUpdate = sendCancelToLoadBalancer(
      pendingDeploy
    );

    final DeployState deployState = SingularityDeployCheckHelper.interpretLoadBalancerState(
      lbUpdate,
      DeployState.CANCELING
    );

    updatePendingDeploy(pendingDeploy, Optional.of(lbUpdate), deployState);

    return new SingularityDeployResult(deployState, lbUpdate, deployFailures);
  }

  private SingularityDeployResult getDeployResultSafe(
    final SingularityRequest request,
    final RequestState requestState,
    final Optional<SingularityDeployMarker> cancelRequest,
    final SingularityPendingDeploy pendingDeploy,
    final Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest,
    final Optional<SingularityDeploy> deploy,
    final Collection<SingularityTaskId> deployActiveTasks,
    final Collection<SingularityTaskId> otherActiveTasks,
    final Collection<SingularityTaskId> inactiveDeployMatchingTasks
  ) {
    try {
      return getDeployResult(
        request,
        requestState,
        cancelRequest,
        pendingDeploy,
        updatePendingDeployRequest,
        deploy,
        deployActiveTasks,
        otherActiveTasks,
        inactiveDeployMatchingTasks
      );
    } catch (Exception e) {
      LOG.error(
        "Uncaught exception processing deploy {} - {}",
        pendingDeploy.getDeployMarker().getRequestId(),
        pendingDeploy.getDeployMarker().getDeployId(),
        e
      );
      return new SingularityDeployResult(
        DeployState.FAILED_INTERNAL_STATE,
        String.format("Uncaught exception: %s", e.getMessage())
      );
    }
  }

  private SingularityDeployResult getDeployResult(
    final SingularityRequest request,
    final RequestState requestState,
    final Optional<SingularityDeployMarker> cancelRequest,
    final SingularityPendingDeploy pendingDeploy,
    final Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest,
    final Optional<SingularityDeploy> deploy,
    final Collection<SingularityTaskId> deployActiveTasks,
    final Collection<SingularityTaskId> otherActiveTasks,
    final Collection<SingularityTaskId> inactiveDeployMatchingTasks
  ) {
    if (
      !request.isDeployable() ||
      (
        configuration.isAllowDeployOfPausedRequests() &&
        requestState == RequestState.PAUSED
      )
    ) {
      LOG.info(
        "Succeeding a deploy {} because the request {} was not deployable",
        pendingDeploy,
        request
      );

      return new SingularityDeployResult(DeployState.SUCCEEDED, "Request not deployable");
    }

    if (!deploy.isPresent()) {
      // Check for abandoned pending deploy
      Optional<SingularityDeployResult> result = deployManager.getDeployResult(
        request.getId(),
        pendingDeploy.getDeployMarker().getDeployId()
      );
      if (result.isPresent() && result.get().getDeployState().isDeployFinished()) {
        LOG.info(
          "Deploy was already finished, running cleanup of pending data for {}",
          pendingDeploy.getDeployMarker()
        );
        return result.get();
      }
    }

    if (!pendingDeploy.getDeployProgress().isPresent()) {
      return new SingularityDeployResult(
        DeployState.FAILED,
        "No deploy progress data present in Zookeeper. Please reattempt your deploy"
      );
    }

    Set<SingularityTaskId> newInactiveDeployTasks = SingularityDeployCheckHelper.getNewInactiveDeployTasks(
      pendingDeploy,
      inactiveDeployMatchingTasks
    );

    if (!newInactiveDeployTasks.isEmpty()) {
      if (
        CanaryDeployHelper.canRetryTasks(deploy, inactiveDeployMatchingTasks, taskManager)
      ) {
        SingularityDeployProgress newProgress = pendingDeploy
          .getDeployProgress()
          .get()
          .withFailedTasks(new HashSet<>(inactiveDeployMatchingTasks));
        updatePendingDeploy(
          pendingDeploy,
          pendingDeploy.getLastLoadBalancerUpdate(),
          DeployState.WAITING,
          Optional.of(newProgress)
        );
        requestManager.addToPendingQueue(
          new SingularityPendingRequest(
            request.getId(),
            pendingDeploy.getDeployMarker().getDeployId(),
            System.currentTimeMillis(),
            pendingDeploy.getDeployMarker().getUser(),
            PendingType.NEXT_DEPLOY_STEP,
            deploy.isPresent()
              ? deploy.get().getSkipHealthchecksOnDeploy()
              : Optional.empty(),
            pendingDeploy.getDeployMarker().getMessage()
          )
        );
        return new SingularityDeployResult(DeployState.WAITING);
      }

      if (
        request.isLoadBalanced() &&
        SingularityDeployCheckHelper.shouldCancelLoadBalancer(pendingDeploy)
      ) {
        LOG.info(
          "Attempting to cancel pending load balancer request, failing deploy {} regardless",
          pendingDeploy
        );
        sendCancelToLoadBalancer(pendingDeploy);
      }

      int maxRetries = CanaryDeployHelper.getMaxRetries(deploy);
      return deployCheckHelper.getDeployResultWithFailures(
        request,
        deploy,
        pendingDeploy,
        DeployState.FAILED,
        String.format(
          "%s task(s) for this deploy failed",
          inactiveDeployMatchingTasks.size() - maxRetries
        ),
        inactiveDeployMatchingTasks
      );
    }

    return checkDeployProgress(
      request,
      cancelRequest,
      pendingDeploy,
      updatePendingDeployRequest,
      deploy,
      deployActiveTasks,
      otherActiveTasks
    );
  }

  private SingularityDeployResult checkDeployProgress(
    final SingularityRequest request,
    final Optional<SingularityDeployMarker> cancelRequest,
    final SingularityPendingDeploy pendingDeploy,
    final Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest,
    final Optional<SingularityDeploy> deploy,
    final Collection<SingularityTaskId> deployActiveTasks,
    final Collection<SingularityTaskId> otherActiveTasks
  ) {
    SingularityDeployProgress deployProgress = pendingDeploy.getDeployProgress().get();

    if (cancelRequest.isPresent()) {
      LOG.info(
        "Canceling a deploy {} due to cancel request {}",
        pendingDeploy,
        cancelRequest.get()
      );
      String userMessage = cancelRequest.get().getUser().isPresent()
        ? String.format(" by %s", cancelRequest.get().getUser().get())
        : "";
      return new SingularityDeployResult(
        DeployState.CANCELED,
        Optional.of(
          String.format(
            "Canceled due to request%s at %s",
            userMessage,
            cancelRequest.get().getTimestamp()
          )
        ),
        pendingDeploy.getLastLoadBalancerUpdate(),
        Collections.emptyList(),
        System.currentTimeMillis()
      );
    }

    if (deployProgress.isStepComplete()) {
      return checkCanMoveToNextDeployStep(
        request,
        deploy,
        pendingDeploy,
        updatePendingDeployRequest
      );
    }

    final boolean isDeployOverdue = deployCheckHelper.isDeployOverdue(
      pendingDeploy,
      deploy
    );
    if (SingularityDeployCheckHelper.shouldCheckLbState(pendingDeploy)) {
      final SingularityLoadBalancerUpdate lbUpdate = lbClient.getState(
        SingularityDeployCheckHelper.getLoadBalancerRequestId(pendingDeploy)
      );
      return processLbState(
        request,
        deploy,
        pendingDeploy,
        updatePendingDeployRequest,
        deployActiveTasks,
        otherActiveTasks,
        CanaryDeployHelper.tasksToShutDown(deployProgress, otherActiveTasks, request),
        lbUpdate
      );
    }

    if (
      isDeployOverdue &&
      request.isLoadBalanced() &&
      SingularityDeployCheckHelper.shouldCancelLoadBalancer(pendingDeploy)
    ) {
      return cancelLoadBalancer(
        pendingDeploy,
        deployCheckHelper.getDeployFailures(
          request,
          deploy,
          pendingDeploy,
          DeployState.OVERDUE,
          deployActiveTasks
        )
      );
    }

    if (deployActiveTasks.size() < deployProgress.getTargetActiveInstances()) {
      maybeUpdatePendingRequest(
        pendingDeploy,
        deploy,
        request,
        updatePendingDeployRequest
      );
      return checkOverdue(
        request,
        deploy,
        pendingDeploy,
        deployActiveTasks,
        isDeployOverdue
      );
    }

    if (isWaitingForCurrentLbRequest(pendingDeploy)) {
      return new SingularityDeployResult(
        DeployState.WAITING,
        Optional.of("Waiting on load balancer API"),
        pendingDeploy.getLastLoadBalancerUpdate()
      );
    }

    final DeployHealth deployHealth = deployHealthHelper.getDeployHealth(
      request,
      deploy,
      deployActiveTasks,
      true
    );
    switch (deployHealth) {
      case WAITING:
        maybeUpdatePendingRequest(
          pendingDeploy,
          deploy,
          request,
          updatePendingDeployRequest
        );
        return checkOverdue(
          request,
          deploy,
          pendingDeploy,
          deployActiveTasks,
          isDeployOverdue
        );
      case HEALTHY:
        if (!request.isLoadBalanced()) {
          return markStepFinished(
            pendingDeploy,
            deploy,
            deployActiveTasks,
            otherActiveTasks,
            request,
            updatePendingDeployRequest
          );
        }

        if (
          updatePendingDeployRequest.isPresent() &&
          updatePendingDeployRequest.get().getTargetActiveInstances() !=
          deployProgress.getTargetActiveInstances()
        ) {
          maybeUpdatePendingRequest(
            pendingDeploy,
            deploy,
            request,
            updatePendingDeployRequest
          );
          return new SingularityDeployResult(DeployState.WAITING);
        }

        if (configuration.getLoadBalancerUri() == null) {
          LOG.warn(
            "Deploy {} required a load balancer URI but it wasn't set",
            pendingDeploy
          );
          return new SingularityDeployResult(
            DeployState.FAILED,
            Optional.of("No valid load balancer URI was present"),
            Optional.<SingularityLoadBalancerUpdate>empty(),
            Collections.<SingularityDeployFailure>emptyList(),
            System.currentTimeMillis()
          );
        }

        for (SingularityTaskId activeTaskId : deployActiveTasks) {
          taskManager.markHealthchecksFinished(activeTaskId);
          taskManager.clearStartupHealthchecks(activeTaskId);
        }

        return enqueueAndProcessLbRequest(
          request,
          deploy,
          pendingDeploy,
          updatePendingDeployRequest,
          deployActiveTasks,
          otherActiveTasks
        );
      case UNHEALTHY:
      default:
        for (SingularityTaskId activeTaskId : deployActiveTasks) {
          taskManager.markHealthchecksFinished(activeTaskId);
          taskManager.clearStartupHealthchecks(activeTaskId);
        }
        return deployCheckHelper.getDeployResultWithFailures(
          request,
          deploy,
          pendingDeploy,
          DeployState.FAILED,
          "Not all tasks for deploy were healthy",
          deployActiveTasks
        );
    }
  }

  private SingularityDeployResult checkCanMoveToNextDeployStep(
    SingularityRequest request,
    Optional<SingularityDeploy> deploy,
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest
  ) {
    SingularityDeployProgress deployProgress = pendingDeploy.getDeployProgress().get();
    if (
      CanaryDeployHelper.canMoveToNextStep(deployProgress) ||
      updatePendingDeployRequest.isPresent()
    ) {
      SingularityDeployProgress newProgress = deployProgress.withNewTargetInstances(
        CanaryDeployHelper.getNewTargetInstances(
          deployProgress,
          request,
          updatePendingDeployRequest
        )
      );
      updatePendingDeploy(
        pendingDeploy,
        pendingDeploy.getLastLoadBalancerUpdate(),
        DeployState.WAITING,
        Optional.of(newProgress)
      );
      requestManager.addToPendingQueue(
        new SingularityPendingRequest(
          request.getId(),
          pendingDeploy.getDeployMarker().getDeployId(),
          System.currentTimeMillis(),
          pendingDeploy.getDeployMarker().getUser(),
          PendingType.NEXT_DEPLOY_STEP,
          deploy.isPresent()
            ? deploy.get().getSkipHealthchecksOnDeploy()
            : Optional.empty(),
          pendingDeploy.getDeployMarker().getMessage()
        )
      );
    }
    return new SingularityDeployResult(DeployState.WAITING);
  }

  // TODO canary rollout style updates
  private SingularityDeployResult enqueueAndProcessLbRequest(
    SingularityRequest request,
    Optional<SingularityDeploy> deploy,
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest,
    Collection<SingularityTaskId> deployActiveTasks,
    Collection<SingularityTaskId> otherActiveTasks
  ) {
    Collection<SingularityTaskId> toShutDown = CanaryDeployHelper.tasksToShutDown(
      pendingDeploy.getDeployProgress().get(),
      otherActiveTasks,
      request
    );
    final Map<SingularityTaskId, SingularityTask> tasks = taskManager.getTasks(
      Iterables.concat(deployActiveTasks, toShutDown)
    );
    final LoadBalancerRequestId lbRequestId = SingularityDeployCheckHelper.getLoadBalancerRequestId(
      pendingDeploy
    );

    List<SingularityTaskId> toRemoveFromLb = new ArrayList<>();
    for (SingularityTaskId taskId : toShutDown) {
      Optional<SingularityLoadBalancerUpdate> maybeAddUpdate = taskManager.getLoadBalancerState(
        taskId,
        LoadBalancerRequestType.ADD
      );
      if (
        maybeAddUpdate.isPresent() &&
        (
          maybeAddUpdate.get().getLoadBalancerState() == BaragonRequestState.SUCCESS ||
          maybeAddUpdate.get().getLoadBalancerState().isInProgress()
        )
      ) {
        toRemoveFromLb.add(taskId);
      }
    }

    updateLoadBalancerStateForTasks(
      deployActiveTasks,
      LoadBalancerRequestType.ADD,
      SingularityLoadBalancerUpdate.preEnqueue(lbRequestId)
    );
    updateLoadBalancerStateForTasks(
      toRemoveFromLb,
      LoadBalancerRequestType.REMOVE,
      SingularityLoadBalancerUpdate.preEnqueue(lbRequestId)
    );
    SingularityLoadBalancerUpdate enqueueResult = lbClient.enqueue(
      lbRequestId,
      request,
      deploy.get(),
      SingularityDeployCheckHelper.getTasks(deployActiveTasks, tasks),
      SingularityDeployCheckHelper.getTasks(toShutDown, tasks)
    );
    return processLbState(
      request,
      deploy,
      pendingDeploy,
      updatePendingDeployRequest,
      deployActiveTasks,
      otherActiveTasks,
      toShutDown,
      enqueueResult
    );
  }

  private SingularityDeployResult processLbState(
    SingularityRequest request,
    Optional<SingularityDeploy> deploy,
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest,
    Collection<SingularityTaskId> deployActiveTasks,
    Collection<SingularityTaskId> otherActiveTasks,
    Collection<SingularityTaskId> tasksToShutDown,
    SingularityLoadBalancerUpdate lbUpdate
  ) {
    List<SingularityTaskId> toRemoveFromLb = new ArrayList<>();
    for (SingularityTaskId taskId : tasksToShutDown) {
      Optional<SingularityLoadBalancerUpdate> maybeRemoveUpdate = taskManager.getLoadBalancerState(
        taskId,
        LoadBalancerRequestType.REMOVE
      );
      if (
        maybeRemoveUpdate.isPresent() &&
        maybeRemoveUpdate
          .get()
          .getLoadBalancerRequestId()
          .getId()
          .equals(lbUpdate.getLoadBalancerRequestId().getId())
      ) {
        toRemoveFromLb.add(taskId);
      }
    }

    updateLoadBalancerStateForTasks(
      deployActiveTasks,
      LoadBalancerRequestType.ADD,
      lbUpdate
    );
    updateLoadBalancerStateForTasks(
      toRemoveFromLb,
      LoadBalancerRequestType.REMOVE,
      lbUpdate
    );

    DeployState deployState = SingularityDeployCheckHelper.interpretLoadBalancerState(
      lbUpdate,
      pendingDeploy.getCurrentDeployState()
    );
    if (deployState == DeployState.SUCCEEDED) {
      updatePendingDeploy(pendingDeploy, Optional.of(lbUpdate), DeployState.WAITING); // A step has completed, markStepFinished will determine SUCCEEDED/WAITING
      return markStepFinished(
        pendingDeploy,
        deploy,
        deployActiveTasks,
        otherActiveTasks,
        request,
        updatePendingDeployRequest
      );
    } else if (deployState == DeployState.WAITING) {
      updatePendingDeploy(pendingDeploy, Optional.of(lbUpdate), deployState);
      maybeUpdatePendingRequest(
        pendingDeploy,
        deploy,
        request,
        updatePendingDeployRequest,
        Optional.of(lbUpdate)
      );
      return new SingularityDeployResult(DeployState.WAITING);
    } else {
      updatePendingDeploy(pendingDeploy, Optional.of(lbUpdate), deployState);
      maybeUpdatePendingRequest(
        pendingDeploy,
        deploy,
        request,
        updatePendingDeployRequest,
        Optional.of(lbUpdate)
      );
      return new SingularityDeployResult(
        deployState,
        lbUpdate,
        SingularityDeployFailure.lbUpdateFailed()
      );
    }
  }

  private void maybeUpdatePendingRequest(
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityDeploy> deploy,
    SingularityRequest request,
    Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest
  ) {
    maybeUpdatePendingRequest(
      pendingDeploy,
      deploy,
      request,
      updatePendingDeployRequest,
      Optional.empty()
    );
  }

  private void maybeUpdatePendingRequest(
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityDeploy> deploy,
    SingularityRequest request,
    Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest,
    Optional<SingularityLoadBalancerUpdate> lbUpdate
  ) {
    if (
      updatePendingDeployRequest.isPresent() &&
      pendingDeploy.getDeployProgress().isPresent()
    ) {
      SingularityDeployProgress newProgress = pendingDeploy
        .getDeployProgress()
        .get()
        .withNewTargetInstances(
          Math.min(
            updatePendingDeployRequest.get().getTargetActiveInstances(),
            request.getInstancesSafe()
          )
        );
      updatePendingDeploy(
        pendingDeploy,
        lbUpdate.isPresent() ? lbUpdate : pendingDeploy.getLastLoadBalancerUpdate(),
        DeployState.WAITING,
        Optional.of(newProgress)
      );
      requestManager.addToPendingQueue(
        new SingularityPendingRequest(
          request.getId(),
          pendingDeploy.getDeployMarker().getDeployId(),
          System.currentTimeMillis(),
          pendingDeploy.getDeployMarker().getUser(),
          PendingType.NEXT_DEPLOY_STEP,
          deploy.isPresent()
            ? deploy.get().getSkipHealthchecksOnDeploy()
            : Optional.empty(),
          pendingDeploy.getDeployMarker().getMessage()
        )
      );
    }
  }

  private boolean isWaitingForCurrentLbRequest(SingularityPendingDeploy pendingDeploy) {
    return (
      pendingDeploy.getLastLoadBalancerUpdate().isPresent() &&
      SingularityDeployCheckHelper
        .getLoadBalancerRequestId(pendingDeploy)
        .getId()
        .equals(
          pendingDeploy
            .getLastLoadBalancerUpdate()
            .get()
            .getLoadBalancerRequestId()
            .getId()
        ) &&
      pendingDeploy.getLastLoadBalancerUpdate().get().getLoadBalancerState() ==
      BaragonRequestState.WAITING
    );
  }

  private boolean isLastStepFinished(
    SingularityDeployProgress deployProgress,
    SingularityRequest request
  ) {
    return (
      deployProgress.isStepComplete() &&
      deployProgress.getTargetActiveInstances() >= request.getInstancesSafe()
    );
  }

  private SingularityDeployResult markStepFinished(
    SingularityPendingDeploy pendingDeploy,
    Optional<SingularityDeploy> deploy,
    Collection<SingularityTaskId> deployActiveTasks,
    Collection<SingularityTaskId> otherActiveTasks,
    SingularityRequest request,
    Optional<SingularityUpdatePendingDeployRequest> updatePendingDeployRequest
  ) {
    SingularityDeployProgress deployProgress = pendingDeploy.getDeployProgress().get();

    if (
      updatePendingDeployRequest.isPresent() &&
      CanaryDeployHelper.getNewTargetInstances(
        deployProgress,
        request,
        updatePendingDeployRequest
      ) !=
      deployProgress.getTargetActiveInstances()
    ) {
      maybeUpdatePendingRequest(
        pendingDeploy,
        deploy,
        request,
        updatePendingDeployRequest
      );
      return new SingularityDeployResult(DeployState.WAITING);
    }

    SingularityDeployProgress newProgress = deployProgress
      .withNewActiveInstances(deployActiveTasks.size())
      .withCompletedStep();
    DeployState deployState = isLastStepFinished(newProgress, request)
      ? DeployState.SUCCEEDED
      : DeployState.WAITING;

    String message = deployState == DeployState.SUCCEEDED
      ? "New deploy succeeded"
      : "New deploy is progressing, this task is being replaced";

    updatePendingDeploy(
      pendingDeploy,
      pendingDeploy.getLastLoadBalancerUpdate(),
      deployState,
      Optional.of(newProgress)
    );
    for (SingularityTaskId taskId : CanaryDeployHelper.tasksToShutDown(
      deployProgress,
      otherActiveTasks,
      request
    )) {
      taskManager.createTaskCleanup(
        new SingularityTaskCleanup(
          Optional.empty(),
          TaskCleanupType.DEPLOY_STEP_FINISHED,
          System.currentTimeMillis(),
          taskId,
          Optional.of(message),
          Optional.empty(),
          Optional.empty()
        )
      );
    }
    return new SingularityDeployResult(deployState);
  }

  private SingularityDeployResult checkOverdue(
    SingularityRequest request,
    Optional<SingularityDeploy> deploy,
    SingularityPendingDeploy pendingDeploy,
    Collection<SingularityTaskId> deployActiveTasks,
    boolean isOverdue
  ) {
    String message = null;

    if (deploy.isPresent()) {
      message =
        String.format(
          "Deploy was able to launch %s tasks, but not all of them became healthy within %s",
          deployActiveTasks.size(),
          JavaUtils.durationFromMillis(deployCheckHelper.getAllowedMillis(deploy.get()))
        );
    }

    if (isOverdue) {
      if (deploy.isPresent()) {
        return deployCheckHelper.getDeployResultWithFailures(
          request,
          deploy,
          pendingDeploy,
          DeployState.OVERDUE,
          message,
          deployActiveTasks
        );
      } else {
        return new SingularityDeployResult(DeployState.OVERDUE);
      }
    } else {
      return new SingularityDeployResult(DeployState.WAITING);
    }
  }
}
