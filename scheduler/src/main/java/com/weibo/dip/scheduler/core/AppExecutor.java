package com.weibo.dip.scheduler.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.google.common.base.Preconditions;
import com.weibo.dip.data.platform.commons.ClasspathProperties;
import com.weibo.dip.data.platform.commons.Symbols;
import com.weibo.dip.data.platform.commons.util.DatetimeUtil;
import com.weibo.dip.data.platform.commons.util.GsonUtil;
import com.weibo.dip.scheduler.bean.Application;
import com.weibo.dip.scheduler.bean.ApplicationDependency;
import com.weibo.dip.scheduler.bean.ApplicationRecord;
import com.weibo.dip.scheduler.bean.ApplicationState;
import com.weibo.dip.scheduler.bean.ScheduleApplication;
import com.weibo.dip.scheduler.db.SchedulerOperator;
import com.weibo.dip.scheduler.queue.Message;
import com.weibo.dip.scheduler.queue.MessageQueue;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.quartz.CronScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerUtils;
import org.quartz.spi.OperableTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application executor.
 *
 * @author yurun
 */
public class AppExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(AppExecutor.class);

  private static final long DEFAULT_TIME_SLEEP = 10 * 1000;
  private static final int DEFAULT_EXECUTE_DELAY = 3 * 60 * 1000;

  private static final int DEFAULT_CPU_SHARES = 1024;

  private static final String CREATED = "created";
  private static final String RUNNING = "running";
  private static final String EXITED = "exited";

  private ClasspathProperties properties;

  private int cores;
  private int mems;

  private AtomicInteger usedCores = new AtomicInteger(0);
  private AtomicInteger usedMems = new AtomicInteger(0);

  private MessageQueue queue;
  private SchedulerOperator operator;

  private static class QueueResource {
    private String queue;

    private int cores;
    private int mems;

    private double percent;

    public QueueResource(String queue, int cores, int mems) {
      this.queue = queue;
      this.cores = cores;
      this.mems = mems;
    }

    public String getQueue() {
      return queue;
    }

    public int getCores() {
      return cores;
    }

    public int getMems() {
      return mems;
    }

    public double getPercent() {
      return percent;
    }

    public void setPercent(double percent) {
      this.percent = percent;
    }
  }

  private class Submitter extends Thread {

    private String getQueueWithLeastUsedResource() throws Exception {
      // get queues
      List<String> queues = queue.queues();
      if (CollectionUtils.isEmpty(queues)) {
        LOGGER.debug("Message queue is empty!");

        return null;
      }

      List<QueueResource> queueResources = new ArrayList<>();

      int cores = 0;
      int mems = 0;

      // get queue resources
      for (String queue : queues) {
        int usedCores = operator.getUsedCores(queue);
        int usedMems = operator.getUsedMems(queue);

        cores += usedCores;
        mems += usedMems;

        queueResources.add(new QueueResource(queue, usedCores, usedMems));
      }

      // compute queue resource percents
      for (QueueResource queueResource : queueResources) {
        double usedCorePercent = queueResource.getCores() / 1.0 / cores;
        double usedMemPercent = queueResource.getMems() / 1.0 / mems;

        // choose the largest of cores and mems
        queueResource.setPercent(
            usedCorePercent > usedMemPercent ? usedCorePercent : usedMemPercent);
      }

      // sort message queue by resource percent asc
      queueResources.sort(Comparator.comparingDouble(QueueResource::getPercent));

      return queueResources.get(0).getQueue();
    }

    private boolean checkRunningConditions(ScheduleApplication scheduleApplication) {
      // check cores
      if (usedCores.get() + scheduleApplication.getCores() > cores) {
        LOGGER.info(
            "Insufficient cores required by the application {}, need: {}, used: {}, total: {}",
            scheduleApplication.getUniqeName(),
            scheduleApplication.getCores(),
            usedCores.get(),
            cores);

        return false;
      }

      // check mems
      if (usedMems.get() + scheduleApplication.getMems() > mems) {
        LOGGER.info(
            "Insufficient mems required by the application {}, need: {}, used: {}, total: {}",
            scheduleApplication.getUniqeName(),
            scheduleApplication.getMems(),
            usedMems.get(),
            mems);

        return false;
      }

      // check dag
      try {
        List<ApplicationDependency> dependencies =
            operator.getDependencies(scheduleApplication.getName(), scheduleApplication.getQueue());
        if (CollectionUtils.isEmpty(dependencies)) {
          return true;
        }

        Date scheduleTime = scheduleApplication.getApplicationRecord().getScheduleTime();

        for (ApplicationDependency dependency : dependencies) {
          String dependedName = dependency.getDependName();
          String dependedQueue = dependency.getDependQueue();

          Application dependedApplication = operator.getApplication(dependedName, dependedQueue);
          Preconditions.checkState(
              Objects.nonNull(dependedApplication),
              "Application %s_%s depends on %s_%s, but %s_%s does not exist",
              scheduleApplication.getName(),
              scheduleApplication.getQueue(),
              dependedName,
              dependedQueue,
              dependedName,
              dependedQueue);

          String dependedCron = dependedApplication.getCron();

          int fromSeconds = dependency.getFromSeconds();
          int toSeconds = dependency.getToSeconds();

          List<Date> fireTimes =
              TriggerUtils.computeFireTimesBetween(
                  (OperableTrigger)
                      TriggerBuilder.newTrigger()
                          .withSchedule(CronScheduleBuilder.cronSchedule(dependedCron))
                          .build(),
                  null,
                  DateUtils.addSeconds(scheduleTime, -fromSeconds), // include
                  DateUtils.addSeconds(scheduleTime, -toSeconds)); // include
          if (CollectionUtils.isEmpty(fireTimes)) {
            LOGGER.info(
                "Application {} depends on {}({}), but the lap [{},{}] is too close",
                scheduleApplication.getUniqeName(),
                dependedApplication.getUniqeName(),
                dependedCron,
                fromSeconds,
                toSeconds);

            continue;
          }

          for (Date fireTime : fireTimes) {
            ApplicationRecord record =
                operator.getApplicationRecord(dependedName, dependedQueue, fireTime);
            if (Objects.isNull(record)) {
              LOGGER.info(
                  "Application {} depends on {}({}), but no record found",
                  scheduleApplication.getUniqeName(),
                  dependedApplication.getUniqeName(),
                  DatetimeUtil.ISO_8601_EXTENDED_DATETIME_FORMAT.format(fireTime));

              return false;
            } else if (record.getState() != ApplicationState.SUCCESS) {
              LOGGER.info(
                  "Application {} depends on {}({}), but record shows {}",
                  scheduleApplication.getUniqeName(),
                  dependedApplication.getUniqeName(),
                  DatetimeUtil.ISO_8601_EXTENDED_DATETIME_FORMAT.format(fireTime),
                  record.getState().name());
              return false;
            }
          }
        }
      } catch (Exception e) {
        LOGGER.error(
            "Application {} check dag error: {}",
            scheduleApplication.getUniqeName(),
            ExceptionUtils.getStackTrace(e));

        return false;
      }

      return true;
    }

    @Override
    public void run() {
      while (!isInterrupted()) {
        // resource exhaustion?
        if (usedCores.get() >= cores || usedMems.get() >= mems) {
          LOGGER.warn(
              "Node resources are exhausted, userd: ({},{}), total: ({},{})",
              usedCores.get(),
              usedMems.get(),
              cores,
              mems);

          try {
            Thread.sleep(DEFAULT_TIME_SLEEP);
          } catch (InterruptedException e) {
            LOGGER.warn("Submitter wait for resources, but interrupted");
            interrupt();
          }

          continue;
        }

        try {
          // get the queue with the least used resources
          String queueName = getQueueWithLeastUsedResource();
          if (Objects.isNull(queueName)) {
            LOGGER.debug("Can't find the queue with the least resources, wait...");

            try {
              Thread.sleep(DEFAULT_TIME_SLEEP);
            } catch (InterruptedException e) {
              LOGGER.warn("Submitter wait for the queue with least resources, but interrupted");

              interrupt();
            }

            continue;
          }

          LOGGER.debug("Queue {} uses the least resources", queueName);

          // get a message from the queue that uses the least resources
          Message message = queue.consume(queueName);
          if (Objects.isNull(message)) {
            LOGGER.debug(
                "Message in queue {} may be delayed, or acquired by other nodes", queueName);

            try {
              Thread.sleep(DEFAULT_TIME_SLEEP);
            } catch (InterruptedException e) {
              LOGGER.warn("Submitter wait for a message, but interrupted");

              interrupt();
            }

            continue;
          }

          // deserialize message to application
          ScheduleApplication scheduleApplication =
              GsonUtil.fromJson(message.getMessage(), ScheduleApplication.class);

          updateScheduleApplicationState(scheduleApplication, ApplicationState.PENDING);
          LOGGER.info("Application {} taked from queue", scheduleApplication.getUniqeName());

          if (checkRunningConditions(scheduleApplication)) {
            // run
            usedCores.addAndGet(scheduleApplication.getCores());
            usedMems.addAndGet(scheduleApplication.getMems());

            LOGGER.info(
                "Application {} meet running conditions, submit to run",
                scheduleApplication.getUniqeName());

            executors.submit(new Executor(scheduleApplication));
          } else {
            // delay
            updateScheduleApplicationState(scheduleApplication, ApplicationState.QUEUED);

            queue.produce(
                GsonUtil.toJson(scheduleApplication),
                scheduleApplication.getQueue(),
                scheduleApplication.getPriority(),
                DateUtils.addMilliseconds(new Date(), DEFAULT_EXECUTE_DELAY));

            LOGGER.info(
                "Application {} does not meet running conditions, deley to queue",
                scheduleApplication.getUniqeName());
          }
        } catch (Exception e) {
          LOGGER.error("Submitter run error: {}", ExceptionUtils.getStackTrace(e));
        }
      }
    }
  }

  private Submitter submitter = new Submitter();

  private class Executor implements Runnable {
    private DockerClient dockerClient;

    private ScheduleApplication scheduleApplication;

    public Executor(ScheduleApplication scheduleApplication) {
      this.scheduleApplication = scheduleApplication;
    }

    private boolean checkStatus(String containerId, String status) {
      List<Container> containers =
          dockerClient
              .listContainersCmd()
              .withShowAll(true)
              .withIdFilter(Collections.singleton(containerId))
              .withStatusFilter(Collections.singleton(status))
              .exec();

      return CollectionUtils.isNotEmpty(containers);
    }

    private int getExitCode(String containerId) {
      List<Container> containers =
          dockerClient
              .listContainersCmd()
              .withShowAll(true)
              .withIdFilter(Collections.singleton(containerId))
              .withStatusFilter(Collections.singleton(EXITED))
              .exec();

      Preconditions.checkState(
          CollectionUtils.isNotEmpty(containers),
          "Application {} container does not exit",
          scheduleApplication.getUniqeName());

      Container exitedContainer = containers.get(0);

      String status = exitedContainer.getStatus();

      String code = status.substring(status.indexOf("(") + 1, status.indexOf(")"));

      return Integer.valueOf(code);
    }

    private String getLog(String containerId) throws Exception {
      List<String> logs = new ArrayList<>();

      dockerClient
          .logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withTailAll()
          .exec(
              new LogContainerResultCallback() {
                @Override
                public void onNext(Frame item) {
                  logs.add(item.toString());
                }
              })
          .awaitCompletion();

      return StringUtils.join(logs, Symbols.NEWLINE);
    }

    private void execute() throws Exception {
      // pull image
      List<Image> images =
          dockerClient
              .listImagesCmd()
              .withShowAll(true)
              .withImageNameFilter(
                  scheduleApplication.getRepository()
                      + Symbols.COLON
                      + scheduleApplication.getTag())
              .exec();

      if (CollectionUtils.isNotEmpty(images)) {
        LOGGER.info(
            "Application {} image {}:{} already exist",
            scheduleApplication.getUniqeName(),
            scheduleApplication.getRepository(),
            scheduleApplication.getTag());
      } else {
        boolean pulled =
            dockerClient
                .pullImageCmd(scheduleApplication.getRepository())
                .withTag(scheduleApplication.getTag())
                .exec(new PullImageResultCallback())
                .awaitCompletion(
                    properties.getLong("docker.image.pull.timeout"), TimeUnit.MILLISECONDS);

        Preconditions.checkState(
            pulled,
            "Application {} pull image {}:{} failed or timeout, check!",
            scheduleApplication.getUniqeName(),
            scheduleApplication.getRepository(),
            scheduleApplication.getTag());

        LOGGER.info(
            "Application {} image {}:{} pulled",
            scheduleApplication.getUniqeName(),
            scheduleApplication.getRepository(),
            scheduleApplication.getTag());
      }

      // container log
      String containerLog =
          properties.getString("docker.container.log")
              + Symbols.SLASH
              + scheduleApplication.getName()
              + Symbols.SLASH
              + DatetimeUtil.DATETIME_FORMAT.format(
                  scheduleApplication.getApplicationRecord().getScheduleTime());

      FileUtils.deleteDirectory(new File(containerLog));
      FileUtils.forceMkdir(new File(containerLog));

      LOGGER.info(
          "Application {} log directory {} created",
          scheduleApplication.getUniqeName(),
          containerLog);

      // shell params
      List<String> cmds = new ArrayList<>();

      cmds.add("python");
      cmds.add(properties.getString("docker.container.python"));
      cmds.add(scheduleApplication.getName());
      cmds.add(scheduleApplication.getQueue());
      cmds.add(scheduleApplication.getUser());
      cmds.add(
          String.valueOf(scheduleApplication.getApplicationRecord().getScheduleTime().getTime()));
      cmds.add(containerLog);

      if (ArrayUtils.isNotEmpty(scheduleApplication.getParams())) {
        cmds.addAll(Arrays.asList(scheduleApplication.getParams()));
      }

      String cmd = StringUtils.join(cmds, Symbols.SPACE);

      LOGGER.info("Application {} container command: {}", scheduleApplication.getUniqeName(), cmd);

      long beginTime = System.currentTimeMillis();
      long timeout = scheduleApplication.getTimeout() * 1000;

      // create container
      CreateContainerResponse container =
          dockerClient
              .createContainerCmd(
                  scheduleApplication.getRepository()
                      + Symbols.COLON
                      + scheduleApplication.getTag())
              .withName(scheduleApplication.getUniqeName())
              .withCpuShares(scheduleApplication.getCores() * DEFAULT_CPU_SHARES)
              .withMemory(scheduleApplication.getMems() * 1024 * 1024L)
              .withBinds(Bind.parse(containerLog + Symbols.COLON + containerLog))
              .withCmd(cmds)
              .exec();

      String containerId = container.getId();

      Preconditions.checkState(
          checkStatus(containerId, CREATED),
          "Application {} container {} created failed",
          scheduleApplication.getUniqeName(),
          containerId);

      LOGGER.info(
          "Application {} container {} created", scheduleApplication.getUniqeName(), containerId);

      try {
        dockerClient.startContainerCmd(containerId).exec();

        Preconditions.checkState(
            checkStatus(containerId, RUNNING),
            "Application {} container does not start",
            scheduleApplication.getUniqeName());

        LOGGER.info(
            "Application {} container {} started", scheduleApplication.getUniqeName(), containerId);

        while (true) {
          if (checkStatus(containerId, RUNNING)) {
            if (System.currentTimeMillis() >= (beginTime + timeout)) {
              dockerClient.killContainerCmd(containerId).exec();

              throw new TimeoutException(
                  "Application "
                      + scheduleApplication.getUniqeName()
                      + " container "
                      + containerId
                      + " execute timeout, killed");
            }
          } else {
            int code = getExitCode(containerId);

            if (code == 0) {
              LOGGER.info(
                  "Application {} container {} executed success",
                  scheduleApplication.getUniqeName(),
                  containerId);
            } else {
              LOGGER.error(
                  "Application {} container execute failed, container log: {}",
                  scheduleApplication.getUniqeName(),
                  getLog(containerId));

              throw new Exception(
                  "Application "
                      + scheduleApplication.getUniqeName()
                      + " container "
                      + containerId
                      + " exit code "
                      + code);
            }

            break;
          }
        }
      } finally {
        dockerClient.removeContainerCmd(containerId).exec();
        LOGGER.info(
            "Application {} container {} removed", scheduleApplication.getUniqeName(), containerId);
      }
    }

    @Override
    public void run() {
      LOGGER.info("Application {} begin to run", scheduleApplication.getUniqeName());

      // docker client
      dockerClient = DockerClientBuilder.getInstance().build();
      LOGGER.info("Application {} docker client builded", scheduleApplication.getUniqeName());

      try {
        updateScheduleApplicationState(scheduleApplication, ApplicationState.RUNNING);

        execute();

        updateScheduleApplicationState(scheduleApplication, ApplicationState.SUCCESS);
      } catch (SQLException se) {
        LOGGER.error(
            "Application {} update record error: {}",
            scheduleApplication.getUniqeName(),
            ExceptionUtils.getStackTrace(se));
      } catch (TimeoutException te) {
        LOGGER.error(
            "Application {} execute timeout: {}",
            scheduleApplication.getUniqeName(),
            ExceptionUtils.getStackTrace(te));

        try {
          updateScheduleApplicationState(scheduleApplication, ApplicationState.KILLED);
        } catch (SQLException se) {
          LOGGER.error(
              "Application {} execute timeout, update application record error: {}",
              scheduleApplication.getUniqeName(),
              ExceptionUtils.getStackTrace(se));
        }
      } catch (Exception e) {
        LOGGER.error(
            "Application execute {} error: {}",
            scheduleApplication.getUniqeName(),
            ExceptionUtils.getStackTrace(e));

        try {
          updateScheduleApplicationState(scheduleApplication, ApplicationState.FAILED);
        } catch (SQLException se) {
          LOGGER.error(
              "Application execute {} faild, update application record error: {}",
              scheduleApplication.getUniqeName(),
              ExceptionUtils.getStackTrace(se));
        }
      } finally {
        usedCores.addAndGet(-scheduleApplication.getCores());
        usedMems.addAndGet(-scheduleApplication.getMems());

        try {
          dockerClient.close();
          LOGGER.info("Application {} docker client closed", scheduleApplication.getUniqeName());
        } catch (IOException e) {
          LOGGER.error(
              "Applicatioin {} docker client close error: {}",
              scheduleApplication.getUniqeName(),
              ExceptionUtils.getStackTrace(e));
        }

        LOGGER.info("Application {} end to run", scheduleApplication.getUniqeName());
      }
    }
  }

  private ExecutorService executors = Executors.newCachedThreadPool();

  /**
   * Construct a instance.
   *
   * @param queue message queue
   * @param operator scheduler operator
   */
  public AppExecutor(
      ClasspathProperties properties, MessageQueue queue, SchedulerOperator operator) {
    this.properties = properties;

    cores = properties.getInt("server.cores");
    mems = properties.getInt("server.mems");

    this.queue = queue;
    this.operator = operator;
  }

  private void updateScheduleApplicationState(
      ScheduleApplication scheduleApplication, ApplicationState state) throws SQLException {
    ApplicationRecord applicationRecord = scheduleApplication.getApplicationRecord();

    applicationRecord.setUpdateTime(new Date());
    applicationRecord.setState(state);

    operator.addOrUpdateApplicationRecord(applicationRecord);
  }

  /** start executor. */
  public void start() {
    submitter.start();
  }

  /** stop executor. */
  public void stop() {
    submitter.interrupt();

    executors.shutdown();
    while (!executors.isTerminated()) {
      try {
        executors.awaitTermination(DEFAULT_TIME_SLEEP, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        LOGGER.warn("await executors to termination, but interrupted");
      }
    }
  }
}
